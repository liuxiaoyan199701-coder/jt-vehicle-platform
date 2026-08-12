package io.github.jtplatform.media.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.RecordingTimeRange;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingSearchServiceTest {
    private static final long START_US = 1_700_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsMergedAvailableRangesAndExcludesOtherStreams() throws Exception {
        RecordingProperties properties = properties(Duration.ofNanos(10_000), Duration.ofNanos(20_000));
        StreamKey requested = new StreamKey("device/search", 1, StreamKind.MAIN);
        StreamKey other = new StreamKey("device/search", 2, StreamKind.MAIN);
        try (RecordSink sink = new RecordSink(properties)) {
            sink.accept(audio(requested, START_US));
            sink.accept(audio(requested, START_US + 11));
            sink.accept(audio(other, START_US + 5));
        }

        List<RecordingTimeRange> ranges = new RecordingSearchService(properties)
                .search(requested, START_US - 100, START_US + 100);

        assertEquals(List.of(new RecordingTimeRange(START_US, START_US + 11)), ranges);
    }

    @Test
    void skipsCorruptAndUncommittedSegmentsAndReturnsEmptyForMissingRecording() throws Exception {
        RecordingProperties properties = properties(Duration.ofSeconds(30), Duration.ZERO);
        StreamKey key = new StreamKey("device-2", 1, StreamKind.PLAYBACK);
        properties.setRealtimeEnabled(false);
        properties.setPlaybackEnabled(true);
        try (RecordSink sink = new RecordSink(properties)) {
            sink.accept(audio(key, START_US));
        }
        Path marker;
        try (var paths = Files.walk(properties.getRoot())) {
            marker = paths.filter(path -> path.getFileName().toString().endsWith(".ok"))
                    .findFirst()
                    .orElseThrow();
        }
        Path corruptMarker = marker.resolveSibling("corrupt.ok");
        Files.writeString(corruptMarker, "{}");
        Files.writeString(marker.resolveSibling("unfinished.jtr.part"), "JTR1");

        RecordingSearchService service = new RecordingSearchService(properties);

        assertEquals(List.of(new RecordingTimeRange(START_US, START_US)),
                service.search(key, START_US, START_US));
        assertEquals(List.of(), service.search(
                new StreamKey("missing", 1, StreamKind.MAIN), START_US, START_US + 1));
    }

    @Test
    void allSourceSearchMergesCoverageAcrossRealtimeAndPlaybackRecordings() throws Exception {
        RecordingProperties properties = properties(Duration.ofSeconds(30), Duration.ofNanos(1_000));
        properties.setPlaybackEnabled(true);
        StreamKey main = new StreamKey("device-all", 4, StreamKind.MAIN);
        StreamKey playback = new StreamKey("device-all", 4, StreamKind.PLAYBACK);
        try (RecordSink sink = new RecordSink(properties)) {
            sink.accept(audio(main, START_US));
            sink.accept(audio(playback, START_US + 1));
        }

        assertEquals(List.of(new RecordingTimeRange(START_US, START_US + 1)),
                new RecordingSearchService(properties).searchAll(
                        "device-all", 4, START_US - 1, START_US + 2));
    }

    private RecordingProperties properties(Duration segmentDuration, Duration mergeTolerance) {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("recordings"));
        properties.setRealtimeEnabled(true);
        properties.setSegmentDuration(segmentDuration);
        properties.setSearchMergeTolerance(mergeTolerance);
        return properties;
    }

    private static MediaFrame audio(StreamKey key, long timestampUs) {
        return new MediaFrame(
                key, MediaFrameType.AUDIO, MediaCodec.G711A, timestampUs, new byte[] {1, 2, 3});
    }
}
