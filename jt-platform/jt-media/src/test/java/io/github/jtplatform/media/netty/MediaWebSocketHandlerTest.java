package io.github.jtplatform.media.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.auth.InMemoryStreamTokenStore;
import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.port.InMemoryMediaInstanceRegistry;
import io.github.jtplatform.common.port.InMemoryStreamRegistry;
import io.github.jtplatform.common.port.StreamCommandPort;
import io.github.jtplatform.common.port.StreamSubscriptionPort;
import io.github.jtplatform.common.service.MediaScheduler;
import io.github.jtplatform.common.service.StreamCoordinator;
import io.github.jtplatform.media.sink.WebSocketRawSink;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import io.github.jtplatform.media.recording.RecordSink;
import io.github.jtplatform.media.recording.RecordingPlaybackService;
import io.github.jtplatform.media.talkback.TalkbackProperties;
import io.github.jtplatform.media.talkback.TalkbackService;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.ReferenceCountUtil;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaWebSocketHandlerTest {
    private static final StreamKey STREAM = new StreamKey("device-1", 1, StreamKind.MAIN);
    private static final long PLAYBACK_START_US = 1_700_000_000_000_000L;
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void releaseChannels() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    }

    @Test
    void authorizedHandshakeAutomaticallySubscribesBoundStream() {
        WebSocketRawSink sink = new WebSocketRawSink();
        EmbeddedChannel channel = channel(new MediaWebSocketHandler(sink));
        channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(STREAM);

        handshake(channel);

        TextWebSocketFrame state = channel.readOutbound();
        assertTrue(state.text().contains("\"state\":\"waking\""));
        state.release();
        assertEquals(1, sink.subscriberCount(STREAM));
    }

    @Test
    void explicitUnsubscribeAndDisconnectReleaseAnOpenedSubscriptionOnlyOnce() {
        WebSocketRawSink sink = new WebSocketRawSink();
        TrackingSubscriptions subscriptions = new TrackingSubscriptions();
        EmbeddedChannel channel = channel(new MediaWebSocketHandler(
                sink, null, null, null, subscriptions));
        channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(STREAM);
        handshake(channel);
        ReferenceCountUtil.safeRelease(channel.readOutbound());

        channel.writeInbound(new TextWebSocketFrame("{\"action\":\"unsubscribe\"}"));
        ReferenceCountUtil.safeRelease(channel.readOutbound());
        channel.writeInbound(new TextWebSocketFrame("{\"action\":\"unsubscribe\"}"));
        ReferenceCountUtil.safeRelease(channel.readOutbound());
        channel.close().syncUninterruptibly();
        channel.pipeline().fireChannelInactive();

        assertEquals(List.of(STREAM), subscriptions.released);
        assertEquals(0, sink.subscriberCount(STREAM));
    }

    @Test
    void releaseFailureStillCleansTheLocalSubscriptionAndIsNotRetriedOnDisconnect() {
        WebSocketRawSink sink = new WebSocketRawSink();
        AtomicInteger releaseAttempts = new AtomicInteger();
        StreamSubscriptionPort failingSubscriptions = streamKey -> {
            releaseAttempts.incrementAndGet();
            throw new IllegalStateException("release unavailable");
        };
        EmbeddedChannel channel = channel(new MediaWebSocketHandler(
                sink, null, null, null, failingSubscriptions));
        channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(STREAM);
        handshake(channel);
        ReferenceCountUtil.safeRelease(channel.readOutbound());

        channel.writeInbound(new TextWebSocketFrame("{\"action\":\"unsubscribe\"}"));
        ReferenceCountUtil.safeRelease(channel.readOutbound());
        channel.close().syncUninterruptibly();
        channel.pipeline().fireChannelInactive();

        assertEquals(1, releaseAttempts.get());
        assertEquals(0, sink.subscriberCount(STREAM));
    }

    @Test
    void invalidPlaybackRangeReleasesTheOpenedSubscriptionOnlyOnce() {
        StreamKey playback = new StreamKey("device-playback", 2, StreamKind.PLAYBACK);
        TrackingSubscriptions subscriptions = new TrackingSubscriptions();
        EmbeddedChannel channel = channel(new MediaWebSocketHandler(
                new WebSocketRawSink(), null, null, null, subscriptions));
        channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(playback);

        handshake(channel, "/ws?deviceId=device-playback&channel=2&streamKind=playback"
                + "&startTime=20&endTime=10");

        TextWebSocketFrame error = channel.readOutbound();
        assertTrue(error.text().contains("INVALID_PLAYBACK_RANGE"));
        error.release();
        channel.close().syncUninterruptibly();
        channel.pipeline().fireChannelInactive();
        assertEquals(List.of(playback), subscriptions.released);
    }

    @Test
    void switchingAwayFromAnOpenedSubscriptionReleasesOnlyThePreviousStream() {
        StreamKey replacement = new StreamKey("device-2", 2, StreamKind.SUB);
        TrackingSubscriptions subscriptions = new TrackingSubscriptions();
        WebSocketRawSink sink = new WebSocketRawSink();
        EmbeddedChannel channel = channel(new MediaWebSocketHandler(
                sink, null, null, null, subscriptions));
        channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(STREAM);
        handshake(channel);
        ReferenceCountUtil.safeRelease(channel.readOutbound());

        channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(null);
        channel.writeInbound(new TextWebSocketFrame(
                "{\"action\":\"subscribe\",\"deviceId\":\"device-2\"," 
                        + "\"channel\":2,\"streamKind\":\"sub\"}"));
        ReferenceCountUtil.safeRelease(channel.readOutbound());
        channel.close().syncUninterruptibly();

        assertEquals(List.of(STREAM), subscriptions.released);
        assertEquals(0, sink.subscriberCount(STREAM));
        assertEquals(0, sink.subscriberCount(replacement));
    }

    @Test
    void disconnectReachesCoordinatorAndTriggersDelayedClose() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        InMemoryMediaInstanceRegistry instances = new InMemoryMediaInstanceRegistry();
        instances.register(new MediaInstance(
                "media-1", "127.0.0.1", MediaPorts.forInstance(1),
                10, 1_000_000, 0, 0, clock.instant(), false));
        CountingCommands commands = new CountingCommands();
        try (var executor = Executors.newSingleThreadScheduledExecutor()) {
            StreamCoordinator coordinator = new StreamCoordinator(
                    streams,
                    new MediaScheduler(instances, streams, clock, Duration.ofSeconds(15), 0.9),
                    commands,
                    executor,
                    clock,
                    Duration.ofMillis(30),
                    Duration.ofSeconds(5));
            coordinator.open(STREAM);
            coordinator.onFirstPacket(STREAM, "media-1");
            WebSocketRawSink sink = new WebSocketRawSink();
            EmbeddedChannel channel = channel(new MediaWebSocketHandler(
                    sink, streams, null, null, coordinator::release));
            channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(STREAM);
            handshake(channel);
            ReferenceCountUtil.safeRelease(channel.readOutbound());

            channel.close().syncUninterruptibly();
            channel.pipeline().fireChannelInactive();

            assertEquals(0, streams.find(STREAM).orElseThrow().subscriberCount());
            assertTrue(await(() -> commands.closeCount.get() == 1, Duration.ofSeconds(1)));
            assertEquals(1, commands.closeCount.get());
        }
    }

    @Test
    void authorizationFailureReturnsErrorAndClosesWithPrivateCode() {
        EmbeddedChannel channel = channel(new MediaWebSocketHandler(new WebSocketRawSink()));
        channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZATION_FAILURE).set("AUTH_TOKEN_EXPIRED");

        handshake(channel);

        TextWebSocketFrame error = channel.readOutbound();
        CloseWebSocketFrame close = channel.readOutbound();
        assertTrue(error.text().contains("AUTH_TOKEN_EXPIRED"));
        assertEquals(4003, close.statusCode());
        assertEquals("AUTH_TOKEN_EXPIRED", close.reasonText());
        error.release();
        close.release();
        assertFalse(channel.isActive());
    }

    @Test
    void tokenBoundSessionCannotSwitchToAnotherStream() {
        WebSocketRawSink sink = new WebSocketRawSink();
        EmbeddedChannel channel = channel(new MediaWebSocketHandler(sink));
        channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(STREAM);
        handshake(channel);
        ReferenceCountUtil.safeRelease(channel.readOutbound());

        channel.writeInbound(new TextWebSocketFrame(
                "{\"action\":\"subscribe\",\"deviceId\":\"device-1\","
                        + "\"channel\":2,\"streamKind\":\"main\"}"));

        TextWebSocketFrame error = channel.readOutbound();
        assertTrue(error.text().contains("AUTH_STREAM_MISMATCH"));
        error.release();
        assertEquals(1, sink.subscriberCount(STREAM));
        assertEquals(0, sink.subscriberCount(new StreamKey("device-1", 2, StreamKind.MAIN)));
    }

    @Test
    void legacyLogicalChannelSubscriptionIsRejected() {
        WebSocketRawSink sink = new WebSocketRawSink();
        EmbeddedChannel channel = channel(new MediaWebSocketHandler(sink));
        handshake(channel);
        ReferenceCountUtil.safeRelease(channel.readOutbound());

        channel.writeInbound(new TextWebSocketFrame(
                "{\"action\":\"subscribe\",\"deviceId\":\"device-1\","
                        + "\"channel\":1,\"logicalChannel\":0}"));

        TextWebSocketFrame error = channel.readOutbound();
        assertTrue(error.text().contains("INVALID_SUBSCRIPTION"));
        error.release();
        assertEquals(0, sink.subscriberCount(STREAM));
    }

    @Test
    void pendingFailureIsPushedToWaitingSubscriber() {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        streams.getOrRegister(STREAM, () -> new StreamEntry(
                STREAM,
                "stream-1",
                "media-1",
                new MediaTarget("media-1", "127.0.0.1", 7811, 0, 7815),
                Clock.systemUTC()));
        WebSocketRawSink sink = new WebSocketRawSink();
        EmbeddedChannel channel = channel(new MediaWebSocketHandler(sink, streams));
        channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(STREAM);
        handshake(channel);

        TextWebSocketFrame waking = channel.readOutbound();
        assertTrue(waking.text().contains("waking"));
        waking.release();
        streams.markDead(STREAM, "DEVICE_NO_RESPONSE");
        channel.runPendingTasks();

        TextWebSocketFrame error = channel.readOutbound();
        assertTrue(error.text().contains("DEVICE_NO_RESPONSE"));
        error.release();
    }

    @Test
    void establishedSessionIsNotRevalidatedAfterTokenExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        InMemoryStreamTokenStore tokens = new InMemoryStreamTokenStore(new SecureRandom(), clock);
        String token = tokens.issue(STREAM, "media-1", Duration.ofSeconds(1));
        EmbeddedChannel channel = channel(
                new MediaWebSocketAuthenticationHandler(true, tokens, "media-1"),
                new MediaWebSocketHandler(new WebSocketRawSink()));
        var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/ws?deviceId=device-1&channel=1&streamKind=main&token=" + token);
        channel.writeInbound(request);
        ReferenceCountUtil.safeRelease(channel.readInbound());
        handshake(channel);
        ReferenceCountUtil.safeRelease(channel.readOutbound());

        clock.set(Instant.parse("2026-08-10T00:00:02Z"));
        channel.writeInbound(new TextWebSocketFrame("{\"action\":\"ping\"}"));

        TextWebSocketFrame pong = channel.readOutbound();
        assertTrue(pong.text().contains("pong"));
        pong.release();
        assertTrue(channel.isActive());
    }

    @Test
    void binaryTalkbackUploadWithoutATalkbackSubscriptionIsRejected() {
        TalkbackService talkback = new TalkbackService(new TalkbackProperties(), Clock.systemUTC());
        try {
            EmbeddedChannel channel = channel(
                    new MediaWebSocketHandler(new WebSocketRawSink(), null, talkback));
            handshake(channel);
            ReferenceCountUtil.safeRelease(channel.readOutbound());

            channel.writeInbound(new BinaryWebSocketFrame(
                    io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {1, 2, 3})));

            TextWebSocketFrame error = channel.readOutbound();
            assertTrue(error.text().contains("TALKBACK_NOT_SUBSCRIBED"));
            error.release();
        } finally {
            talkback.close();
        }
    }

    @Test
    void exclusiveTalkbackReturnsBusyUntilTheCurrentSessionUnsubscribes() {
        TalkbackService talkback = new TalkbackService(new TalkbackProperties(), Clock.systemUTC());
        try {
            WebSocketRawSink sink = new WebSocketRawSink();
            EmbeddedChannel first = channel(new MediaWebSocketHandler(sink, null, talkback));
            EmbeddedChannel second = channel(new MediaWebSocketHandler(sink, null, talkback));
            handshake(first);
            handshake(second);
            ReferenceCountUtil.safeRelease(first.readOutbound());
            ReferenceCountUtil.safeRelease(second.readOutbound());
            String subscribe = "{\"action\":\"subscribe\",\"deviceId\":\"013800138000\","
                    + "\"channel\":1,\"streamKind\":\"talkback\"}";

            first.writeInbound(new TextWebSocketFrame(subscribe));
            first.runPendingTasks();
            ReferenceCountUtil.safeRelease(first.readOutbound());
            second.writeInbound(new TextWebSocketFrame(subscribe));

            TextWebSocketFrame busy = second.readOutbound();
            assertTrue(busy.text().contains("TALKBACK_BUSY"));
            busy.release();

            first.writeInbound(new TextWebSocketFrame("{\"action\":\"unsubscribe\"}"));
            ReferenceCountUtil.safeRelease(first.readOutbound());
            second.writeInbound(new TextWebSocketFrame(subscribe));
            second.runPendingTasks();

            TextWebSocketFrame waking = second.readOutbound();
            assertTrue(waking.text().contains("\"state\":\"waking\""));
            waking.release();
        } finally {
            talkback.close();
        }
    }

    @Test
    void busyTalkbackHandshakeReleasesTheOpenedSubscriptionOnlyOnce() {
        TalkbackService talkback = new TalkbackService(new TalkbackProperties(), Clock.systemUTC());
        try {
            StreamKey talkbackStream = new StreamKey("013800138000", 1, StreamKind.TALKBACK);
            String subscribe = "{\"action\":\"subscribe\",\"deviceId\":\"013800138000\","
                    + "\"channel\":1,\"streamKind\":\"talkback\"}";
            EmbeddedChannel current = channel(new MediaWebSocketHandler(
                    new WebSocketRawSink(), null, talkback));
            handshake(current);
            ReferenceCountUtil.safeRelease(current.readOutbound());
            current.writeInbound(new TextWebSocketFrame(subscribe));
            ReferenceCountUtil.safeRelease(current.readOutbound());

            TrackingSubscriptions subscriptions = new TrackingSubscriptions();
            EmbeddedChannel rejected = channel(new MediaWebSocketHandler(
                    new WebSocketRawSink(), null, talkback, null, subscriptions));
            rejected.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(talkbackStream);
            handshake(rejected);

            TextWebSocketFrame busy = rejected.readOutbound();
            assertTrue(busy.text().contains("TALKBACK_BUSY"));
            busy.release();
            rejected.close().syncUninterruptibly();
            rejected.pipeline().fireChannelInactive();
            assertEquals(List.of(talkbackStream), subscriptions.released);
            assertEquals(1, talkback.participantCount(talkbackStream));
        } finally {
            talkback.close();
        }
    }

    @Test
    void busyCrossStreamSwitchPreservesTheOriginalTalkbackSubscription() {
        TalkbackService talkback = new TalkbackService(new TalkbackProperties(), Clock.systemUTC());
        try {
            WebSocketRawSink sink = new WebSocketRawSink();
            EmbeddedChannel first = channel(new MediaWebSocketHandler(sink, null, talkback));
            EmbeddedChannel second = channel(new MediaWebSocketHandler(sink, null, talkback));
            EmbeddedChannel third = channel(new MediaWebSocketHandler(sink, null, talkback));
            handshake(first);
            handshake(second);
            handshake(third);
            ReferenceCountUtil.safeRelease(first.readOutbound());
            ReferenceCountUtil.safeRelease(second.readOutbound());
            ReferenceCountUtil.safeRelease(third.readOutbound());
            StreamKey original = new StreamKey("013800138000", 1, StreamKind.TALKBACK);
            StreamKey busyTarget = new StreamKey("013800138001", 1, StreamKind.TALKBACK);
            String subscribeOriginal = "{\"action\":\"subscribe\",\"deviceId\":\"013800138000\"," 
                    + "\"channel\":1,\"streamKind\":\"talkback\"}";
            String subscribeBusy = "{\"action\":\"subscribe\",\"deviceId\":\"013800138001\"," 
                    + "\"channel\":1,\"streamKind\":\"talkback\"}";

            first.writeInbound(new TextWebSocketFrame(subscribeOriginal));
            second.writeInbound(new TextWebSocketFrame(subscribeBusy));
            ReferenceCountUtil.safeRelease(first.readOutbound());
            ReferenceCountUtil.safeRelease(second.readOutbound());

            first.writeInbound(new TextWebSocketFrame(subscribeBusy));

            TextWebSocketFrame busy = first.readOutbound();
            assertTrue(busy.text().contains("TALKBACK_BUSY"));
            busy.release();
            assertEquals(1, talkback.participantCount(original));
            assertEquals(1, talkback.participantCount(busyTarget));
            assertEquals(1, sink.subscriberCount(original));
            assertEquals(1, sink.subscriberCount(busyTarget));

            first.writeInbound(new BinaryWebSocketFrame(
                    io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {1})));
            TextWebSocketFrame deviceMissing = first.readOutbound();
            assertTrue(deviceMissing.text().contains("TALKBACK_DEVICE_NOT_CONNECTED"));
            deviceMissing.release();

            third.writeInbound(new TextWebSocketFrame(subscribeOriginal));
            TextWebSocketFrame originalStillBusy = third.readOutbound();
            assertTrue(originalStillBusy.text().contains("TALKBACK_BUSY"));
            originalStillBusy.release();
        } finally {
            talkback.close();
        }
    }

    @Test
    void playbackHandshakeStreamsRecordedFramesControlsTheSessionAndCleansUpOnDisconnect()
            throws Exception {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("recordings"));
        properties.setRealtimeEnabled(true);
        StreamKey recorded = new StreamKey("device-playback", 2, StreamKind.MAIN);
        StreamKey playback = new StreamKey("device-playback", 2, StreamKind.PLAYBACK);
        try (RecordSink recordSink = new RecordSink(properties)) {
            recordSink.accept(frame(recorded, MediaFrameType.SPS, PLAYBACK_START_US - 2, 0x67));
            recordSink.accept(frame(recorded, MediaFrameType.PPS, PLAYBACK_START_US - 1, 0x68));
            recordSink.accept(frame(recorded, MediaFrameType.VIDEO_KEY, PLAYBACK_START_US, 0x65));
            recordSink.accept(frame(recorded, MediaFrameType.VIDEO_KEY,
                    PLAYBACK_START_US + 1_000_000L, 0x65));
            recordSink.accept(frame(recorded, MediaFrameType.VIDEO_DELTA,
                    PLAYBACK_START_US + 6_000_000L, 0x41));
        }

        try (RecordingPlaybackService playbackService = new RecordingPlaybackService(properties)) {
            TrackingSubscriptions subscriptions = new TrackingSubscriptions();
            EmbeddedChannel channel = channel(new MediaWebSocketHandler(
                    new WebSocketRawSink(), null, null, playbackService, subscriptions));
            channel.attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM).set(playback);
            Instant start = instant(PLAYBACK_START_US);
            Instant end = instant(PLAYBACK_START_US + 6_000_000L);

            handshake(channel, "/ws?deviceId=device-playback&channel=2&streamKind=playback"
                    + "&startTime=" + start + "&endTime=" + end);

            assertTrue(awaitBinaryFrames(channel, 3, Duration.ofSeconds(5)) >= 3);
            assertEquals(1, playbackService.activeSessionCount());

            channel.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"playback-control\",\"action\":\"pause\"}"));
            assertTrue(awaitText(channel, "\"state\":\"paused\"", Duration.ofSeconds(5)));

            channel.writeInbound(new TextWebSocketFrame(
                    "{\"type\":\"playback-control\",\"action\":\"seek\","
                            + "\"position\":\"" + instant(PLAYBACK_START_US + 1_000_000L) + "\"}"));
            assertTrue(awaitText(channel, "\"state\":\"waking\"", Duration.ofSeconds(5)));
            assertTrue(awaitBinaryFrames(channel, 3, Duration.ofSeconds(5)) >= 3);

            channel.close().syncUninterruptibly();
            channel.runPendingTasks();
            assertTrue(awaitSessionCount(playbackService, 0, Duration.ofSeconds(5)));
            assertEquals(List.of(playback), subscriptions.released);
            channel.pipeline().fireChannelInactive();
            assertEquals(List.of(playback), subscriptions.released);
        }
    }

    private EmbeddedChannel channel(io.netty.channel.ChannelHandler... handlers) {
        EmbeddedChannel channel = new EmbeddedChannel(handlers);
        channels.add(channel);
        return channel;
    }

    private static void handshake(EmbeddedChannel channel) {
        handshake(channel, "/ws");
    }

    private static void handshake(EmbeddedChannel channel, String requestUri) {
        channel.pipeline().fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete(
                requestUri, EmptyHttpHeaders.INSTANCE, null));
        channel.runPendingTasks();
    }

    private static MediaFrame frame(
            StreamKey streamKey, MediaFrameType type, long timestampUs, int nalUnitType) {
        return new MediaFrame(streamKey, type, MediaCodec.H264, timestampUs,
                new byte[] {0, 0, 0, 1, (byte) nalUnitType, 1});
    }

    private static Instant instant(long timestampUs) {
        return Instant.ofEpochSecond(
                timestampUs / 1_000_000L,
                timestampUs % 1_000_000L * 1_000L);
    }

    private static int awaitBinaryFrames(
            EmbeddedChannel channel, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int count = 0;
        while (System.nanoTime() < deadline && count < expected) {
            channel.runPendingTasks();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                try {
                    if (outbound instanceof BinaryWebSocketFrame) {
                        count++;
                    }
                } finally {
                    ReferenceCountUtil.release(outbound);
                }
            }
            if (count < expected) {
                Thread.sleep(5);
            }
        }
        return count;
    }

    private static boolean awaitText(
            EmbeddedChannel channel, String expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            channel.runPendingTasks();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                try {
                    if (outbound instanceof TextWebSocketFrame text
                            && text.text().contains(expected)) {
                        return true;
                    }
                } finally {
                    ReferenceCountUtil.release(outbound);
                }
            }
            Thread.sleep(5);
        }
        return false;
    }

    private static boolean awaitSessionCount(
            RecordingPlaybackService service, int expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (service.activeSessionCount() == expected) {
                return true;
            }
            Thread.sleep(5);
        }
        return service.activeSessionCount() == expected;
    }

    private static boolean await(
            java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return condition.getAsBoolean();
    }

    private static final class TrackingSubscriptions implements StreamSubscriptionPort {
        private final List<StreamKey> released = new ArrayList<>();

        @Override
        public int release(StreamKey streamKey) {
            released.add(streamKey);
            return released.size();
        }
    }

    private static final class CountingCommands implements StreamCommandPort {
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public io.github.jtplatform.common.model.StreamTicket openLive(
                StreamKey streamKey, MediaTarget target) {
            return null;
        }

        @Override
        public io.github.jtplatform.common.model.StreamTicket openPlayback(
                StreamKey streamKey,
                MediaTarget target,
                java.time.LocalDateTime startTime,
                java.time.LocalDateTime endTime) {
            return null;
        }

        @Override
        public void close(StreamKey streamKey) {
            closeCount.incrementAndGet();
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
