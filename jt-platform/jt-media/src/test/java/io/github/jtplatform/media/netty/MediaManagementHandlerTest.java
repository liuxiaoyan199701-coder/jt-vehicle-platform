package io.github.jtplatform.media.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.config.MediaRuntimeProperties;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.FrameAssembler;
import io.github.jtplatform.media.ingest.FragmentReassembler;
import io.github.jtplatform.media.metrics.MediaNodeLoadMonitor;
import io.github.jtplatform.media.pipeline.FirstFrameListener;
import io.github.jtplatform.media.pipeline.MediaPipeline;
import io.github.jtplatform.media.protocol.FragmentFlag;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.github.jtplatform.media.protocol.Jt1078Header;
import io.github.jtplatform.media.protocol.RtpPacket;
import io.github.jtplatform.media.recording.RecordSink;
import io.github.jtplatform.media.recording.RecordingStorageMetrics;
import io.github.jtplatform.media.sink.SinkRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaManagementHandlerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void capacityEndpointExposesCurrentLoadAndConfiguredLimits() {
        AtomicInteger currentStreams = new AtomicInteger(7);
        AtomicLong outboundBytes = new AtomicLong();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        MediaNodeLoadMonitor monitor = new MediaNodeLoadMonitor(
                currentStreams::get, outboundBytes::get, clock);
        outboundBytes.set(250);
        clock.advance(Duration.ofSeconds(2));
        monitor.sample();
        MediaRuntimeProperties.Capacity capacity = new MediaRuntimeProperties.Capacity();
        capacity.setMaxStreams(250);
        capacity.setMaxOutboundBitsPerSecond(5_000_000);
        EmbeddedChannel channel = new EmbeddedChannel(
                new MediaManagementHandler(MediaPorts.forInstance(4), monitor, capacity));

        channel.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/metrics/capacity"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        String body = response.content().toString(CharsetUtil.UTF_8);
        assertTrue(body.contains("\"currentStreams\":7"));
        assertTrue(body.contains("\"outboundBitsPerSecond\":1000"));
        assertTrue(body.contains("\"maxStreams\":250"));
        assertTrue(body.contains("\"maxOutboundBitsPerSecond\":5000000"));
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void recordingEndpointExposesOccupiedAndRemainingStorage() throws Exception {
        Path root = temporaryDirectory.resolve("recordings");
        Files.createDirectories(root);
        Files.write(root.resolve("segment.jtr.part"), new byte[] {1, 2, 3, 4});
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(root);
        EmbeddedChannel channel = new EmbeddedChannel(new MediaManagementHandler(
                MediaPorts.forInstance(4),
                new MediaNodeLoadMonitor(() -> 0, () -> 0L, Clock.systemUTC()),
                new MediaRuntimeProperties.Capacity(),
                new RecordingStorageMetrics(properties)));

        channel.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/metrics/recording"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(200, response.status().code());
        String body = response.content().toString(CharsetUtil.UTF_8);
        assertTrue(body.contains("\"recordingOccupiedBytes\":4"));
        assertTrue(body.matches(".*\"recordingUsableBytes\":[1-9][0-9]*.*"));
        assertTrue(body.matches(".*\"recordingTotalBytes\":[1-9][0-9]*.*"));
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void manualRecordingEndpointStartsAndStopsOnlyAnActiveStream() {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("manual"));
        properties.setRealtimeEnabled(true);
        properties.setContinuousEnabled(false);
        properties.setManualEnabled(true);
        RecordSink recordSink = new RecordSink(properties);
        SinkRegistry sinks = new SinkRegistry();
        sinks.register(recordSink);
        MediaPipeline pipeline = new MediaPipeline(
                new FragmentReassembler(Duration.ofSeconds(1), 1024, Clock.systemUTC()),
                new FrameAssembler(), sinks, FirstFrameListener.noOp());
        Object ingress = new Object();
        pipeline.accept(ingress, audioPacket("device-1", 1));
        EmbeddedChannel channel = new EmbeddedChannel(new MediaManagementHandler(
                MediaPorts.forInstance(1),
                new MediaNodeLoadMonitor(() -> 1, () -> 0L, Clock.systemUTC()),
                new MediaRuntimeProperties.Capacity(),
                new RecordingStorageMetrics(properties), recordSink, pipeline));
        String query = "?deviceId=device-1&channel=1&streamKind=main";
        StreamKey streamKey = new StreamKey("device-1", 1, StreamKind.MAIN);

        channel.writeInbound(request(HttpMethod.POST, "/recording/manual/start" + query));
        FullHttpResponse started = channel.readOutbound();
        assertEquals(200, started.status().code());
        assertTrue(started.content().toString(CharsetUtil.UTF_8).contains("\"state\":\"recording\""));
        assertTrue(recordSink.isRecording(streamKey));
        started.release();

        channel.writeInbound(request(HttpMethod.POST, "/recording/manual/stop" + query));
        FullHttpResponse stopped = channel.readOutbound();
        assertEquals(200, stopped.status().code());
        assertTrue(stopped.content().toString(CharsetUtil.UTF_8).contains("\"state\":\"stopped\""));
        assertFalse(recordSink.isRecording(streamKey));
        stopped.release();
        pipeline.closeIngress(ingress);
        channel.finishAndReleaseAll();
    }

    @Test
    void manualRecordingEndpointRejectsAStreamThatIsNotLive() {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("inactive"));
        properties.setRealtimeEnabled(true);
        properties.setContinuousEnabled(false);
        properties.setManualEnabled(true);
        RecordSink recordSink = new RecordSink(properties);
        MediaPipeline pipeline = new MediaPipeline(
                new FragmentReassembler(Duration.ofSeconds(1), 1024, Clock.systemUTC()),
                new FrameAssembler(), new SinkRegistry(), FirstFrameListener.noOp());
        EmbeddedChannel channel = new EmbeddedChannel(new MediaManagementHandler(
                MediaPorts.forInstance(1),
                new MediaNodeLoadMonitor(() -> 0, () -> 0L, Clock.systemUTC()),
                new MediaRuntimeProperties.Capacity(), null, recordSink, pipeline));

        channel.writeInbound(request(HttpMethod.POST,
                "/recording/manual/start?deviceId=device-1&channel=1&streamKind=main"));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(409, response.status().code());
        assertTrue(response.content().toString(CharsetUtil.UTF_8).contains("STREAM_NOT_LIVE"));
        response.release();
        channel.finishAndReleaseAll();
    }

    private static FullHttpRequest request(HttpMethod method, String uri) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        return request;
    }

    private static RtpPacket audioPacket(String deviceId, int channel) {
        byte[] payload = {1};
        return new RtpPacket(new Jt1078Header(
                0, Jt1078Constants.PT_G711A, 1, deviceId, channel,
                Jt1078Constants.AUDIO_FRAME, FragmentFlag.ATOMIC,
                1_700_000_000_000L, 0, 0, payload.length),
                StreamKind.MAIN, payload);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
