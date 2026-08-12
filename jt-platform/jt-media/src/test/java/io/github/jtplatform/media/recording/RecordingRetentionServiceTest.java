package io.github.jtplatform.media.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void removesSegmentsPastRetentionDaysAndReportsStorage() throws Exception {
        RecordingProperties properties = properties();
        properties.setRetentionDays(2);
        StreamKey old = new StreamKey("old-device", 1, StreamKind.MAIN);
        StreamKey recent = new StreamKey("recent-device", 1, StreamKind.MAIN);
        try (RecordSink sink = new RecordSink(properties)) {
            sink.accept(audio(old, timestampUs(NOW.minus(Duration.ofDays(3)))));
            sink.accept(audio(recent, timestampUs(NOW.minus(Duration.ofDays(1)))));
        }

        RecordingRetentionResult result = new RecordingRetentionService(
                properties, Clock.fixed(NOW, ZoneOffset.UTC)).cleanup();
        RecordingStorageSnapshot metrics = new RecordingStorageMetrics(properties).snapshot();

        assertEquals(1, result.removedSegments());
        assertTrue(result.occupiedBytesAfter() < result.occupiedBytesBefore());
        assertEquals(result.occupiedBytesAfter(), metrics.occupiedBytes());
        assertTrue(metrics.usableBytes() > 0);
        assertTrue(metrics.totalBytes() >= metrics.usableBytes());
        assertEquals(0, committedSegments(properties.getRoot(), "old-device"));
        assertEquals(1, committedSegments(properties.getRoot(), "recent-device"));
    }

    @Test
    void capacityThresholdDeletesOldestUntilUsageIsBelowLimit() throws Exception {
        RecordingProperties properties = properties();
        StreamKey oldest = new StreamKey("oldest", 1, StreamKind.MAIN);
        StreamKey newest = new StreamKey("newest", 1, StreamKind.MAIN);
        try (RecordSink sink = new RecordSink(properties)) {
            sink.accept(audio(oldest, timestampUs(NOW.minusSeconds(2))));
            sink.accept(audio(newest, timestampUs(NOW.minusSeconds(1))));
        }
        long before = new RecordingStorageMetrics(properties).snapshot().occupiedBytes();
        properties.setMaxBytes(before);

        RecordingRetentionResult result = new RecordingRetentionService(
                properties, Clock.fixed(NOW, ZoneOffset.UTC)).cleanup();

        assertEquals(1, result.removedSegments());
        assertTrue(result.occupiedBytesAfter() < properties.getMaxBytes());
        assertEquals(0, committedSegments(properties.getRoot(), "oldest"));
        assertEquals(1, committedSegments(properties.getRoot(), "newest"));
    }

    private RecordingProperties properties() {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("recordings"));
        properties.setRealtimeEnabled(true);
        return properties;
    }

    private static MediaFrame audio(StreamKey key, long timestampUs) {
        return new MediaFrame(
                key, MediaFrameType.AUDIO, MediaCodec.G711A, timestampUs, new byte[] {1, 2, 3});
    }

    private static long timestampUs(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    private static long committedSegments(Path root, String deviceId) throws Exception {
        Path deviceRoot = root.resolve(deviceId);
        if (!Files.isDirectory(deviceRoot)) {
            return 0;
        }
        try (var paths = Files.walk(deviceRoot)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".ok")).count();
        }
    }
}
