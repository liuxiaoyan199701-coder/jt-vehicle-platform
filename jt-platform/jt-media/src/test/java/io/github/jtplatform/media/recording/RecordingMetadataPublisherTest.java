package io.github.jtplatform.media.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingMetadataPublisherTest {
    private static final long START_US = 1_700_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesMetadataOnlyAfterCommitMarkerExistsWithoutBinaryPayload() throws Exception {
        AtomicReference<MessageEnvelope> published = new AtomicReference<>();
        RecordingMetadataPublisher metadataPublisher = new RecordingMetadataPublisher(envelope -> {
            assertTrue(Files.isRegularFile(Path.of((String) envelope.payload().get("markerPath"))));
            published.set(envelope);
            return PublishResult.of("test", PublishDisposition.ACCEPTED);
        }, "media-7", Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC));
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("recordings"));
        properties.setRealtimeEnabled(true);
        StreamKey key = new StreamKey("device-7", 3, StreamKind.SUB);

        try (RecordSink sink = new RecordSink(properties, metadataPublisher)) {
            sink.accept(new MediaFrame(
                    key, MediaFrameType.AUDIO, MediaCodec.G711A, START_US, new byte[] {1, 2, 3}));
        }

        MessageEnvelope envelope = published.get();
        assertEquals("device-7", envelope.deviceId());
        assertEquals("media-7", envelope.instanceId());
        assertEquals(MessageType.RECORDING_METADATA, envelope.type());
        assertEquals(3, envelope.payload().get("channel"));
        assertEquals("sub", envelope.payload().get("streamKind"));
        assertEquals(START_US, envelope.payload().get("startTimestampUs"));
        assertEquals(1L, envelope.payload().get("frameCount"));
        assertFalse(envelope.payload().values().stream().anyMatch(byte[].class::isInstance));
    }
}
