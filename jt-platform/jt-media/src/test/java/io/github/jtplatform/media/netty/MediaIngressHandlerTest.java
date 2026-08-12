package io.github.jtplatform.media.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.port.InMemoryStreamRegistry;
import io.github.jtplatform.common.port.StreamCommandPort;
import io.github.jtplatform.common.service.MediaScheduler;
import io.github.jtplatform.common.service.StreamCoordinator;
import io.github.jtplatform.media.frame.FrameAssembler;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.ingest.FragmentReassembler;
import io.github.jtplatform.media.pipeline.MediaPipeline;
import io.github.jtplatform.media.protocol.FragmentFlag;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.github.jtplatform.media.protocol.Jt1078Header;
import io.github.jtplatform.media.protocol.RtpPacket;
import io.github.jtplatform.media.sink.SinkRegistry;
import io.github.jtplatform.media.talkback.TalkbackProperties;
import io.github.jtplatform.media.talkback.TalkbackService;
import io.github.jtplatform.media.talkback.TalkbackUploadResult;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class MediaIngressHandlerTest {
    private static final String CURRENT_INSTANCE = "media-1";
    private static final StreamKey STREAM = new StreamKey("13800138000", 1, StreamKind.MAIN);
    private static final Clock CLOCK = Clock.systemUTC();

    @Test
    void matchingOwnershipMarksLiveAndDispatchesTheFirstFrame() {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        register(streams, CURRENT_INSTANCE);
        List<MediaFrame> received = new ArrayList<>();
        EmbeddedChannel channel = channel(streams, received);

        try {
            channel.writeInbound(packet());

            assertTrue(channel.isActive());
            assertEquals(StreamState.LIVE, streams.find(STREAM).orElseThrow().state());
            assertEquals(1, received.size());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void ownershipAssignedToAnotherInstanceClosesConnectionBeforeLiveOrSink() {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        register(streams, "media-2");
        List<MediaFrame> received = new ArrayList<>();
        EmbeddedChannel channel = channel(streams, received);

        try {
            channel.writeInbound(packet());
            channel.runPendingTasks();

            assertFalse(channel.isActive());
            assertEquals(StreamState.PENDING, streams.find(STREAM).orElseThrow().state());
            assertTrue(received.isEmpty());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void missingRegistrationClosesConnectionBeforeSink() {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        List<MediaFrame> received = new ArrayList<>();
        EmbeddedChannel channel = channel(streams, received);

        try {
            channel.writeInbound(packet());
            channel.runPendingTasks();

            assertFalse(channel.isActive());
            assertTrue(streams.find(STREAM).isEmpty());
            assertTrue(received.isEmpty());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void terminalIdInRtpHeaderCannotClaimAStreamRegisteredByMobileNo() {
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        register(streams, CURRENT_INSTANCE);
        List<MediaFrame> received = new ArrayList<>();
        EmbeddedChannel channel = channel(streams, received);

        try {
            channel.writeInbound(packet("1380000"));
            channel.runPendingTasks();

            assertFalse(channel.isActive());
            assertEquals(StreamState.PENDING, streams.find(STREAM).orElseThrow().state());
            assertTrue(received.isEmpty());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void talkbackDeviceIsRegisteredOnlyAfterACompleteFramePassesOwnershipValidation() {
        StreamKey talkbackStream = new StreamKey("013800138000", 1, StreamKind.TALKBACK);
        InMemoryStreamRegistry streams = new InMemoryStreamRegistry();
        streams.getOrRegister(talkbackStream, () -> new StreamEntry(
                talkbackStream,
                "talkback-1",
                CURRENT_INSTANCE,
                new MediaTarget(CURRENT_INSTANCE, "127.0.0.1", 7814, 0, 7815),
                CLOCK));
        SinkRegistry sinks = new SinkRegistry();
        StreamCoordinator coordinator = new StreamCoordinator(
                streams,
                mock(MediaScheduler.class),
                mock(StreamCommandPort.class),
                mock(ScheduledExecutorService.class),
                CLOCK,
                Duration.ofMinutes(1),
                Duration.ofMinutes(1));
        MediaPipeline pipeline = new MediaPipeline(
                new FragmentReassembler(Duration.ofSeconds(1), 1024, CLOCK),
                new FrameAssembler(),
                sinks,
                streamKey -> coordinator.onFirstPacket(streamKey, CURRENT_INSTANCE));
        EmbeddedChannel participant = new EmbeddedChannel();
        TalkbackService talkback = new TalkbackService(new TalkbackProperties(), CLOCK);
        EmbeddedChannel ingress = new EmbeddedChannel(
                new MediaIngressHandler(pipeline, CURRENT_INSTANCE, talkback));

        try {
            assertTrue(talkback.subscribe(talkbackStream, participant));
            ingress.writeInbound(talkbackPacket(talkbackStream, FragmentFlag.FIRST, 10, new byte[] {1}));
            assertEquals(TalkbackUploadResult.DEVICE_NOT_CONNECTED,
                    talkback.upload(talkbackStream, participant, new byte[] {7}));

            ingress.writeInbound(talkbackPacket(talkbackStream, FragmentFlag.LAST, 10, new byte[] {2}));
            assertEquals(TalkbackUploadResult.ACCEPTED,
                    talkback.upload(talkbackStream, participant, new byte[] {7}));
            ingress.runPendingTasks();
            assertTrue(ingress.readOutbound() instanceof io.netty.buffer.ByteBuf);
        } finally {
            talkback.close();
            participant.finishAndReleaseAll();
            ingress.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel(InMemoryStreamRegistry streams, List<MediaFrame> received) {
        SinkRegistry sinks = new SinkRegistry();
        sinks.register(received::add);
        StreamCoordinator coordinator = new StreamCoordinator(
                streams,
                mock(MediaScheduler.class),
                mock(StreamCommandPort.class),
                mock(ScheduledExecutorService.class),
                CLOCK,
                Duration.ofMinutes(1),
                Duration.ofMinutes(1));
        MediaPipeline pipeline = new MediaPipeline(
                new FragmentReassembler(Duration.ofSeconds(1), 1024, CLOCK),
                new FrameAssembler(),
                sinks,
                streamKey -> coordinator.onFirstPacket(streamKey, CURRENT_INSTANCE));
        return new EmbeddedChannel(new MediaIngressHandler(pipeline, CURRENT_INSTANCE));
    }

    private static void register(InMemoryStreamRegistry streams, String owner) {
        streams.getOrRegister(STREAM, () -> new StreamEntry(
                STREAM,
                "stream-1",
                owner,
                new MediaTarget(owner, "127.0.0.1", 7811, 0, 7815),
                CLOCK));
    }

    private static RtpPacket packet() {
        return packet(STREAM.deviceId());
    }

    private static RtpPacket packet(String deviceId) {
        byte[] payload = {1, 2, 3};
        return new RtpPacket(new Jt1078Header(
                0,
                Jt1078Constants.PT_G711A,
                1,
                deviceId,
                STREAM.channel(),
                Jt1078Constants.AUDIO_FRAME,
                FragmentFlag.ATOMIC,
                10,
                0,
                0,
                payload.length), STREAM.streamKind(), payload);
    }

    private static RtpPacket talkbackPacket(
            StreamKey streamKey,
            FragmentFlag fragmentFlag,
            long timestamp,
            byte[] payload) {
        return new RtpPacket(new Jt1078Header(
                0,
                Jt1078Constants.PT_G711A,
                1,
                streamKey.deviceId(),
                streamKey.channel(),
                Jt1078Constants.AUDIO_FRAME,
                fragmentFlag,
                timestamp,
                0,
                0,
                payload.length), streamKey.streamKind(), payload);
    }
}
