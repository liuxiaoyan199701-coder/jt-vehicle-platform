package io.github.jtplatform.media.recording;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import io.github.jtplatform.media.sink.WebSocketRawSink;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingPlaybackServiceTest {
    private static final long START_US = 1_700_000_000_000_000L;
    private static final byte[] SPS = {0, 0, 0, 1, 0x67, 0x01};
    private static final byte[] PPS = {0, 0, 0, 1, 0x68, 0x02};

    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void startsAtNearestKeyFrameAndUsesTheRealtimeBinaryFormat() throws Exception {
        RecordingProperties properties = properties();
        StreamKey key = new StreamKey("device-playback", 3, StreamKind.MAIN);
        writeVideo(properties, key);

        List<MediaFrame> frames = new CopyOnWriteArrayList<>();
        List<byte[]> encoded = new CopyOnWriteArrayList<>();
        RecordingPlaybackOutput binary = RecordingPlaybackOutput.binary(encoded::add);
        RecordingPlaybackOutput output = frame -> {
            frames.add(frame);
            binary.onFrame(frame);
        };

        try (RecordingPlaybackService service = service(properties)) {
            RecordingPlaybackSession session = service.play(
                    new RecordingPlaybackRequest(key, START_US + 25, START_US + 30),
                    output);
            session.completion().get(5, TimeUnit.SECONDS);
        }

        assertEquals(List.of(
                        MediaFrameType.SPS,
                        MediaFrameType.PPS,
                        MediaFrameType.VIDEO_KEY,
                        MediaFrameType.VIDEO_DELTA),
                frames.stream().map(MediaFrame::type).toList());
        assertEquals(List.of(START_US + 20, START_US + 20, START_US + 20, START_US + 30),
                frames.stream().map(MediaFrame::timestamp).toList());
        assertArrayEquals(new byte[] {0, 0, 0, 1, 0x65, 0x20}, frames.get(2).payload());

        List<byte[]> realtime = realtimeBytes(key, frames);
        assertEquals(encoded.size(), realtime.size());
        for (int index = 0; index < encoded.size(); index++) {
            assertArrayEquals(realtime.get(index), encoded.get(index));
            assertArrayEquals(new byte[] {'J', 'T', '7', '8'},
                    java.util.Arrays.copyOf(encoded.get(index), 4));
            assertEquals(key.channel(), Byte.toUnsignedInt(encoded.get(index)[5]));
        }
    }

    @Test
    void playbackStreamRequestCanUseRecordedMainStreamAsItsSource() throws Exception {
        RecordingProperties properties = properties();
        StreamKey recorded = new StreamKey("device-local-playback", 3, StreamKind.MAIN);
        StreamKey playback = new StreamKey("device-local-playback", 3, StreamKind.PLAYBACK);
        writeVideo(properties, recorded);
        List<MediaFrame> frames = new CopyOnWriteArrayList<>();

        try (RecordingPlaybackService service = service(properties)) {
            RecordingPlaybackSession session = service.play(
                    new RecordingPlaybackRequest(playback, START_US + 25, START_US + 30),
                    frames::add);
            session.completion().get(5, TimeUnit.SECONDS);
            assertEquals(playback, session.request().streamKey());
        }

        assertEquals(List.of(
                        MediaFrameType.SPS,
                        MediaFrameType.PPS,
                        MediaFrameType.VIDEO_KEY,
                        MediaFrameType.VIDEO_DELTA),
                frames.stream().map(MediaFrame::type).toList());
        assertEquals(recorded, frames.getFirst().streamKey());
    }

    @Test
    void pausesResumesAndSeeksByReplacingTheOldCursor() throws Exception {
        RecordingProperties properties = properties();
        StreamKey key = new StreamKey("device-control", 1, StreamKind.SUB);
        writeVideo(properties, key);

        AtomicReference<RecordingPlaybackSession> sessionReference = new AtomicReference<>();
        CountDownLatch sessionReady = new CountDownLatch(1);
        CountDownLatch firstPause = new CountDownLatch(1);
        CountDownLatch secondPause = new CountDownLatch(1);
        AtomicBoolean pausedAtFirstKey = new AtomicBoolean();
        AtomicBoolean pausedAtFirstDelta = new AtomicBoolean();
        List<MediaFrame> frames = new CopyOnWriteArrayList<>();
        List<RecordingPlaybackState> states = new CopyOnWriteArrayList<>();

        RecordingPlaybackOutput output = new RecordingPlaybackOutput() {
            @Override
            public void onFrame(MediaFrame frame) {
                await(sessionReady);
                frames.add(frame);
                if (frame.type() == MediaFrameType.VIDEO_KEY
                        && frame.timestamp() == START_US
                        && pausedAtFirstKey.compareAndSet(false, true)) {
                    sessionReference.get().pause();
                    firstPause.countDown();
                } else if (frame.type() == MediaFrameType.VIDEO_DELTA
                        && frame.timestamp() == START_US + 10
                        && pausedAtFirstDelta.compareAndSet(false, true)) {
                    sessionReference.get().pause();
                    secondPause.countDown();
                }
            }

            @Override
            public void onStateChanged(RecordingPlaybackState state) {
                states.add(state);
            }
        };

        try (RecordingPlaybackService service = service(properties)) {
            RecordingPlaybackSession session = service.play(
                    new RecordingPlaybackRequest(key, START_US, START_US + 30), output);
            sessionReference.set(session);
            sessionReady.countDown();

            assertTrue(firstPause.await(5, TimeUnit.SECONDS));
            assertEquals(RecordingPlaybackState.PAUSED, session.state());
            session.resume();

            assertTrue(secondPause.await(5, TimeUnit.SECONDS));
            assertEquals(RecordingPlaybackState.PAUSED, session.state());
            session.seek(START_US + 25);
            session.completion().get(5, TimeUnit.SECONDS);

            assertEquals(RecordingPlaybackState.COMPLETED, session.state());
            assertEquals(START_US + 30, session.positionTimestampUs());
        }

        assertEquals(List.of(
                        MediaFrameType.SPS,
                        MediaFrameType.PPS,
                        MediaFrameType.VIDEO_KEY,
                        MediaFrameType.VIDEO_DELTA,
                        MediaFrameType.SPS,
                        MediaFrameType.PPS,
                        MediaFrameType.VIDEO_KEY,
                        MediaFrameType.VIDEO_DELTA),
                frames.stream().map(MediaFrame::type).toList());
        assertEquals(List.of(
                        START_US,
                        START_US,
                        START_US,
                        START_US + 10,
                        START_US + 20,
                        START_US + 20,
                        START_US + 20,
                        START_US + 30),
                frames.stream().map(MediaFrame::timestamp).toList());
        assertTrue(states.contains(RecordingPlaybackState.PAUSED));
        assertTrue(states.contains(RecordingPlaybackState.STARTING));
        assertEquals(RecordingPlaybackState.COMPLETED, states.getLast());
    }

    @Test
    void continuesInFileOrderAcrossCommittedSegments() throws Exception {
        RecordingProperties properties = properties();
        properties.setSegmentDuration(Duration.ofNanos(15_000));
        StreamKey key = new StreamKey("device-segments", 2, StreamKind.MAIN);
        writeVideo(properties, key);
        List<MediaFrame> frames = new CopyOnWriteArrayList<>();

        try (RecordingPlaybackService service = service(properties)) {
            RecordingPlaybackSession session = service.play(
                    new RecordingPlaybackRequest(key, START_US + 5, START_US + 30),
                    frames::add);
            session.completion().get(5, TimeUnit.SECONDS);
        }

        assertEquals(List.of(
                        MediaFrameType.SPS,
                        MediaFrameType.PPS,
                        MediaFrameType.VIDEO_KEY,
                        MediaFrameType.VIDEO_DELTA,
                        MediaFrameType.SPS,
                        MediaFrameType.PPS,
                        MediaFrameType.VIDEO_KEY,
                        MediaFrameType.VIDEO_DELTA),
                frames.stream().map(MediaFrame::type).toList());
        assertEquals(List.of(
                        START_US,
                        START_US,
                        START_US,
                        START_US + 10,
                        START_US + 20,
                        START_US + 20,
                        START_US + 20,
                        START_US + 30),
                frames.stream().map(MediaFrame::timestamp).toList());
    }

    @Test
    void gapStartStillAnchorsAtTheNearestEarlierKeyFrame() throws Exception {
        RecordingProperties properties = properties();
        properties.setSegmentDuration(Duration.ofNanos(15_000));
        StreamKey key = new StreamKey("device-gap-anchor", 2, StreamKind.MAIN);
        writeVideo(properties, key);
        List<MediaFrame> frames = new CopyOnWriteArrayList<>();

        try (RecordingPlaybackService service = service(properties)) {
            RecordingPlaybackSession session = service.play(
                    new RecordingPlaybackRequest(key, START_US + 15, START_US + 30),
                    frames::add);
            session.completion().get(5, TimeUnit.SECONDS);
        }

        assertEquals(START_US,
                frames.stream()
                        .filter(frame -> frame.type() == MediaFrameType.VIDEO_KEY)
                        .findFirst()
                        .orElseThrow()
                        .timestamp());
    }

    @Test
    void reportsMissingRecordingAndRejectsSeekOutsideTheRequestedRange() throws Exception {
        RecordingProperties properties = properties();
        StreamKey key = new StreamKey("device-validation", 1, StreamKind.MAIN);

        try (RecordingPlaybackService service = service(properties)) {
            assertThrows(RecordingPlaybackException.class, () -> service.play(
                    new RecordingPlaybackRequest(key, START_US, START_US + 30), frame -> { }));
        }

        writeVideo(properties, key);
        CountDownLatch firstFrame = new CountDownLatch(1);
        CountDownLatch releaseFrame = new CountDownLatch(1);
        RecordingPlaybackOutput output = frame -> {
            firstFrame.countDown();
            await(releaseFrame);
        };
        try (RecordingPlaybackService service = service(properties)) {
            RecordingPlaybackSession session = service.play(
                    new RecordingPlaybackRequest(key, START_US, START_US + 30), output);
            assertTrue(firstFrame.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class, () -> session.seek(START_US + 31));
            releaseFrame.countDown();
            session.completion().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void seekWaitsForAnInFlightFrameAndNoOldFrameEscapesAfterItReturns() throws Exception {
        RecordingProperties properties = properties();
        StreamKey key = new StreamKey("device-control-race", 1, StreamKind.MAIN);
        writeVideo(properties, key);

        CountDownLatch frameEntered = new CountDownLatch(1);
        CountDownLatch releaseFrame = new CountDownLatch(1);
        AtomicBoolean firstFrame = new AtomicBoolean(true);
        List<MediaFrame> frames = new CopyOnWriteArrayList<>();
        RecordingPlaybackOutput output = frame -> {
            if (firstFrame.compareAndSet(true, false)) {
                frameEntered.countDown();
                await(releaseFrame);
            }
            frames.add(frame);
        };

        try (RecordingPlaybackService service = service(properties);
                var controls = Executors.newSingleThreadExecutor()) {
            RecordingPlaybackSession session = service.play(
                    new RecordingPlaybackRequest(key, START_US, START_US + 30), output);
            assertTrue(frameEntered.await(5, TimeUnit.SECONDS));

            Future<?> seek = controls.submit(() -> session.seek(START_US + 25));
            assertThrows(TimeoutException.class, () -> seek.get(100, TimeUnit.MILLISECONDS));

            releaseFrame.countDown();
            seek.get(5, TimeUnit.SECONDS);
            int countWhenSeekReturned = frames.size();
            session.completion().get(5, TimeUnit.SECONDS);

            assertEquals(1, countWhenSeekReturned);
            assertEquals(List.of(
                            START_US,
                            START_US + 20,
                            START_US + 20,
                            START_US + 20,
                            START_US + 30),
                    frames.stream().map(MediaFrame::timestamp).toList());
        }
    }

    @Test
    void removesSessionWhenStartingStateCallbackFails() throws Exception {
        RecordingProperties properties = properties();
        StreamKey key = new StreamKey("device-startup-failure", 1, StreamKind.MAIN);
        writeVideo(properties, key);

        try (RecordingPlaybackService service = service(properties)) {
            RecordingPlaybackOutput output = new RecordingPlaybackOutput() {
                @Override
                public void onFrame(MediaFrame frame) {
                }

                @Override
                public void onStateChanged(RecordingPlaybackState state) {
                    if (state == RecordingPlaybackState.STARTING) {
                        throw new IllegalStateException("state destination unavailable");
                    }
                }
            };

            assertThrows(IllegalStateException.class, () -> service.play(
                    new RecordingPlaybackRequest(key, START_US, START_US + 30), output));
            assertEquals(0, service.activeSessionCount());
        }
    }

    private RecordingPlaybackService service(RecordingProperties properties) {
        return new RecordingPlaybackService(
                new RecordingSearchService(properties),
                Executors.newSingleThreadExecutor(),
                false,
                true);
    }

    private static void writeVideo(RecordingProperties properties, StreamKey key) {
        try (RecordSink sink = new RecordSink(properties)) {
            sink.accept(frame(key, MediaFrameType.SPS, START_US - 2, SPS));
            sink.accept(frame(key, MediaFrameType.PPS, START_US - 1, PPS));
            sink.accept(frame(key, MediaFrameType.VIDEO_KEY, START_US,
                    new byte[] {0, 0, 0, 1, 0x65, 0x00}));
            sink.accept(frame(key, MediaFrameType.VIDEO_DELTA, START_US + 10,
                    new byte[] {0, 0, 0, 1, 0x41, 0x10}));
            sink.accept(frame(key, MediaFrameType.VIDEO_KEY, START_US + 20,
                    new byte[] {0, 0, 0, 1, 0x65, 0x20}));
            sink.accept(frame(key, MediaFrameType.VIDEO_DELTA, START_US + 30,
                    new byte[] {0, 0, 0, 1, 0x41, 0x30}));
        }
    }

    private RecordingProperties properties() {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("recordings"));
        properties.setRealtimeEnabled(true);
        properties.setSegmentDuration(Duration.ofSeconds(30));
        return properties;
    }

    private static MediaFrame frame(
            StreamKey key,
            MediaFrameType type,
            long timestampUs,
            byte[] payload) {
        return new MediaFrame(key, type, MediaCodec.H264, timestampUs, payload);
    }

    private static List<byte[]> realtimeBytes(StreamKey key, List<MediaFrame> frames) {
        WebSocketRawSink sink = new WebSocketRawSink();
        EmbeddedChannel channel = new EmbeddedChannel();
        sink.subscribe(key, channel);
        channel.runPendingTasks();
        drainAndRelease(channel);
        for (MediaFrame frame : frames) {
            sink.accept(frame);
            channel.runPendingTasks();
        }

        List<byte[]> encoded = new ArrayList<>();
        Object message;
        while ((message = channel.readOutbound()) != null) {
            try {
                if (message instanceof BinaryWebSocketFrame binary) {
                    byte[] bytes = new byte[binary.content().readableBytes()];
                    binary.content().getBytes(binary.content().readerIndex(), bytes);
                    encoded.add(bytes);
                }
            } finally {
                ReferenceCountUtil.release(message);
            }
        }
        channel.finishAndReleaseAll();
        return encoded;
    }

    private static void drainAndRelease(EmbeddedChannel channel) {
        Object message;
        while ((message = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(message);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for playback test coordination");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("playback test coordination was interrupted", interrupted);
        }
    }
}
