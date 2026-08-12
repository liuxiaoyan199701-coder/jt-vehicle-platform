package io.github.jtplatform.media.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.frame.FrameAssembler;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.ingest.FragmentReassembler;
import io.github.jtplatform.media.protocol.FragmentFlag;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.github.jtplatform.media.protocol.Jt1078Header;
import io.github.jtplatform.media.protocol.RtpPacket;
import io.github.jtplatform.media.sink.MediaSink;
import io.github.jtplatform.media.sink.SinkRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MediaPipelineTest {
    @Test
    void registeredSinkReceivesFramesWithoutChangingDecodeOrReassemblyStages() {
        SinkRegistry sinks = new SinkRegistry();
        List<MediaFrame> firstSink = new ArrayList<>();
        List<MediaFrame> addedSink = new ArrayList<>();
        sinks.register(firstSink::add);
        MediaPipeline pipeline = pipeline(sinks, ignored -> true);

        pipeline.accept(packet(FragmentFlag.ATOMIC, new byte[] {1}));
        sinks.register(addedSink::add);
        pipeline.accept(packet(FragmentFlag.ATOMIC, new byte[] {2}));

        assertEquals(2, firstSink.size());
        assertEquals(1, addedSink.size());
        assertEquals(2, addedSink.getFirst().payload()[0]);
    }

    @Test
    void sinkFailureDoesNotPreventOtherOutputs() {
        SinkRegistry sinks = new SinkRegistry();
        List<MediaFrame> received = new ArrayList<>();
        sinks.register(frame -> { throw new IllegalStateException("disk unavailable"); });
        sinks.register(received::add);

        pipeline(sinks, ignored -> true).accept(packet(FragmentFlag.ATOMIC, new byte[] {1}));

        assertEquals(1, received.size());
    }

    @Test
    void fragmentedInputOnlyReachesSinkAfterLastFragmentAndReportsFirstFrameOnce() {
        SinkRegistry sinks = new SinkRegistry();
        List<MediaFrame> received = new ArrayList<>();
        AtomicInteger firstFrames = new AtomicInteger();
        sinks.register(received::add);
        MediaPipeline pipeline = pipeline(sinks, ignored -> {
            firstFrames.incrementAndGet();
            return true;
        });

        assertTrue(pipeline.accept(packet(FragmentFlag.FIRST, new byte[] {1})).isEmpty());
        assertTrue(pipeline.accept(packet(FragmentFlag.MIDDLE, new byte[] {2})).isEmpty());
        pipeline.accept(packet(FragmentFlag.LAST, new byte[] {3}));
        pipeline.accept(packet(FragmentFlag.ATOMIC, new byte[] {4}));

        assertEquals(2, received.size());
        assertEquals(1, firstFrames.get());
    }

    @Test
    void activeStreamCountTracksDistinctValidIngressStreamsUntilTheirLastConnectionCloses() {
        MediaPipeline pipeline = pipeline(new SinkRegistry(), ignored -> true);
        Object firstIngress = new Object();
        Object secondIngress = new Object();

        pipeline.accept(firstIngress, packet("device-1", FragmentFlag.ATOMIC, new byte[] {1}));
        pipeline.accept(secondIngress, packet("device-1", FragmentFlag.ATOMIC, new byte[] {2}));
        pipeline.accept(firstIngress, packet("device-2", FragmentFlag.ATOMIC, new byte[] {3}));

        assertEquals(2, pipeline.activeStreamCount());
        pipeline.closeIngress(firstIngress);
        assertEquals(1, pipeline.activeStreamCount());
        pipeline.closeIngress(secondIngress);
        assertEquals(0, pipeline.activeStreamCount());
    }

    @Test
    void lastIngressClosureNotifiesSinksExactlyOnce() {
        SinkRegistry sinks = new SinkRegistry();
        AtomicInteger closures = new AtomicInteger();
        sinks.register(new MediaSink() {
            @Override
            public void accept(MediaFrame frame) {
            }

            @Override
            public void onStreamClosed(io.github.jtplatform.common.model.StreamKey streamKey) {
                closures.incrementAndGet();
            }
        });
        MediaPipeline pipeline = pipeline(sinks, ignored -> true);
        Object firstIngress = new Object();
        Object secondIngress = new Object();

        pipeline.accept(firstIngress, packet(FragmentFlag.ATOMIC, new byte[] {1}));
        pipeline.accept(secondIngress, packet(FragmentFlag.ATOMIC, new byte[] {2}));
        pipeline.closeIngress(firstIngress);
        assertEquals(0, closures.get());
        pipeline.closeIngress(secondIngress);
        pipeline.closeIngress(secondIngress);

        assertEquals(1, closures.get());
    }

    @Test
    void ownershipRejectionDoesNotPoisonParameterCacheForTheNextValidIngress() {
        SinkRegistry sinks = new SinkRegistry();
        List<MediaFrame> received = new ArrayList<>();
        AtomicInteger admissions = new AtomicInteger();
        sinks.register(received::add);
        MediaPipeline pipeline = pipeline(sinks, ignored -> admissions.incrementAndGet() > 1);

        assertThrows(StreamOwnershipRejectedException.class,
                () -> pipeline.accept(new Object(), videoPacket()));
        pipeline.accept(new Object(), videoPacket());

        assertEquals(2, received.size());
        assertEquals(io.github.jtplatform.media.frame.MediaFrameType.SPS, received.getFirst().type());
        assertEquals(io.github.jtplatform.media.frame.MediaFrameType.VIDEO_KEY, received.getLast().type());
    }

    private static MediaPipeline pipeline(SinkRegistry sinks, FirstFrameListener listener) {
        return new MediaPipeline(
                new FragmentReassembler(Duration.ofSeconds(2), 1024, Clock.systemUTC()),
                new FrameAssembler(), sinks, listener);
    }

    private static RtpPacket packet(FragmentFlag flag, byte[] payload) {
        return packet("13800138000", flag, payload);
    }

    private static RtpPacket packet(String deviceId, FragmentFlag flag, byte[] payload) {
        return new RtpPacket(new Jt1078Header(
                0, Jt1078Constants.PT_G711A, 1, deviceId, 1,
                Jt1078Constants.AUDIO_FRAME, flag, 10, 0, 0, payload.length),
                StreamKind.MAIN, payload);
    }

    private static RtpPacket videoPacket() {
        byte[] payload = {
                0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f,
                0, 0, 0, 1, 0x65, 0x01, 0x02
        };
        return new RtpPacket(new Jt1078Header(
                0, Jt1078Constants.PT_H264, 1, "13800138000", 1,
                Jt1078Constants.VIDEO_I_FRAME, FragmentFlag.ATOMIC, 10, 0, 0, payload.length),
                StreamKind.MAIN, payload);
    }
}
