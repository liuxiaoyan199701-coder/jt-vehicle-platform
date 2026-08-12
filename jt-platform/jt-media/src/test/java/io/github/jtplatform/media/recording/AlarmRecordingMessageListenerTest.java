package io.github.jtplatform.media.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.FrameAssembler;
import io.github.jtplatform.media.ingest.FragmentReassembler;
import io.github.jtplatform.media.pipeline.FirstFrameListener;
import io.github.jtplatform.media.pipeline.MediaPipeline;
import io.github.jtplatform.media.protocol.FragmentFlag;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.github.jtplatform.media.protocol.Jt1078Header;
import io.github.jtplatform.media.protocol.RtpPacket;
import io.github.jtplatform.media.sink.SinkRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlarmRecordingMessageListenerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void alarmEnvelopeStartsRecordingForEveryActiveStreamOfTheDevice() throws Exception {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("recordings"));
        properties.setRealtimeEnabled(true);
        properties.setContinuousEnabled(false);
        properties.setAlarmEnabled(true);
        RecordSink recordSink = new RecordSink(properties);
        SinkRegistry sinks = new SinkRegistry();
        sinks.register(recordSink);
        MediaPipeline pipeline = new MediaPipeline(
                new FragmentReassembler(Duration.ofSeconds(1), 1024, Clock.systemUTC()),
                new FrameAssembler(), sinks, FirstFrameListener.noOp());
        AlarmRecordingMessageListener listener = new AlarmRecordingMessageListener(
                properties, recordSink, pipeline);
        Object ingress = new Object();
        StreamKey streamKey = new StreamKey("device-1", 1, StreamKind.MAIN);

        pipeline.accept(ingress, audioPacket("device-1", 1, 1, new byte[] {1}));
        assertFalse(recordSink.isRecording(streamKey));

        listener.onMessage(MessageEnvelope.create(
                "device-1", 0x0200, 1, "JT/T 808-2013", Instant.now(),
                "signal-1", MessageType.ALARM, Map.of("alarmFlags", Map.of("emergency", true))));
        assertTrue(recordSink.isRecording(streamKey));
        pipeline.accept(ingress, audioPacket("device-1", 1, 2, new byte[] {2}));
        pipeline.closeIngress(ingress);

        assertFalse(recordSink.isRecording(streamKey));
        try (var files = Files.walk(properties.getRoot())) {
            assertEquals(1, files.filter(path -> path.toString().endsWith(".ok")).count());
        }
    }

    @Test
    void nonAlarmEnvelopeAndDisabledAlarmTriggerDoNothing() {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("disabled"));
        properties.setRealtimeEnabled(true);
        properties.setContinuousEnabled(false);
        RecordSink recordSink = new RecordSink(properties);
        MediaPipeline pipeline = new MediaPipeline(
                new FragmentReassembler(Duration.ofSeconds(1), 1024, Clock.systemUTC()),
                new FrameAssembler(), new SinkRegistry(), FirstFrameListener.noOp());
        Object ingress = new Object();
        pipeline.accept(ingress, audioPacket("device-2", 1, 1, new byte[] {1}));
        AlarmRecordingMessageListener listener = new AlarmRecordingMessageListener(
                properties, recordSink, pipeline);

        listener.onMessage(MessageEnvelope.create(
                "device-2", 0x0200, 1, "JT/T 808-2013", Instant.now(),
                "signal-1", MessageType.LOCATION, Map.of()));
        assertFalse(recordSink.isRecording(new StreamKey("device-2", 1, StreamKind.MAIN)));
    }

    private static RtpPacket audioPacket(
            String deviceId, int channel, int sequence, byte[] payload) {
        return new RtpPacket(new Jt1078Header(
                0, Jt1078Constants.PT_G711A, sequence, deviceId, channel,
                Jt1078Constants.AUDIO_FRAME, FragmentFlag.ATOMIC,
                1_700_000_000_000L + sequence, 0, 0, payload.length),
                StreamKind.MAIN, payload);
    }
}
