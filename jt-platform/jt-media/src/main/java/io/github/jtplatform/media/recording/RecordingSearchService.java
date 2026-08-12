package io.github.jtplatform.media.recording;

import io.github.jtplatform.common.model.RecordingTimeRange;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.port.RecordingCatalog;
import io.github.jtplatform.media.config.RecordingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RecordingSearchService implements RecordingCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingSearchService.class);

    private final Path root;
    private final long mergeToleranceUs;

    public RecordingSearchService(RecordingProperties properties) {
        Objects.requireNonNull(properties, "properties").validate();
        this.root = properties.getRoot().toAbsolutePath().normalize();
        this.mergeToleranceUs = properties.searchMergeToleranceMicros();
    }

    @Override
    public List<RecordingTimeRange> search(
            StreamKey streamKey, long startTimestampUs, long endTimestampUs) throws IOException {
        Objects.requireNonNull(streamKey, "streamKey");
        if (startTimestampUs < 0) {
            throw new IllegalArgumentException("startTimestampUs must not be negative");
        }
        if (endTimestampUs < startTimestampUs) {
            throw new IllegalArgumentException("endTimestampUs must not precede startTimestampUs");
        }

        List<RecordingTimeRange> segments = new ArrayList<>();
        for (RecordingSegmentReference segment : committedSegments(streamKey)) {
            if (segment.endTimestampUs() < startTimestampUs
                    || segment.startTimestampUs() > endTimestampUs) {
                continue;
            }
            segments.add(new RecordingTimeRange(
                    Math.max(startTimestampUs, segment.startTimestampUs()),
                    Math.min(endTimestampUs, segment.endTimestampUs())));
        }
        return merge(segments);
    }

    @Override
    public List<RecordingTimeRange> searchAll(
            String deviceId, int channel, long startTimestampUs, long endTimestampUs) throws IOException {
        List<RecordingTimeRange> ranges = new ArrayList<>();
        for (StreamKind streamKind : List.of(StreamKind.MAIN, StreamKind.SUB, StreamKind.PLAYBACK)) {
            ranges.addAll(search(
                    new StreamKey(deviceId, channel, streamKind),
                    startTimestampUs,
                    endTimestampUs));
        }
        return merge(ranges);
    }

    List<RecordingSegmentReference> committedSegments(StreamKey streamKey) throws IOException {
        Objects.requireNonNull(streamKey, "streamKey");
        Path streamDirectory = streamDirectory(streamKey);
        if (!Files.isDirectory(streamDirectory)) {
            return List.of();
        }

        List<RecordingSegmentReference> segments = new ArrayList<>();
        try (var paths = Files.walk(streamDirectory)) {
            for (Path marker : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ok"))
                    .toList()) {
                RecordingSegmentInspection inspection = RecordingSegmentInspector.inspect(marker);
                if (inspection.status() != RecordingSegmentStatus.COMMITTED) {
                    LOGGER.warn("Skipping unusable recording segment {}: {}",
                            marker, inspection.reason());
                    continue;
                }
                segments.add(new RecordingSegmentReference(
                        inspection.startTimestampUs(),
                        inspection.endTimestampUs(),
                        inspection.frameCount(),
                        inspection.keyFrameCount(),
                        sibling(marker, ".jtr"),
                        sibling(marker, ".jti"),
                        marker));
            }
        }
        segments.sort(Comparator.comparingLong(RecordingSegmentReference::startTimestampUs)
                .thenComparing(segment -> segment.markerPath().toString()));
        return List.copyOf(segments);
    }

    private Path streamDirectory(StreamKey streamKey) {
        Path directory = root
                .resolve(RecordingPathEncoder.encode(streamKey.deviceId()))
                .resolve(Integer.toString(streamKey.channel()))
                .resolve(streamKey.streamKind().wireValue())
                .normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("Recording path escapes configured root");
        }
        return directory;
    }

    private static Path sibling(Path marker, String suffix) {
        String filename = marker.getFileName().toString();
        String base = filename.substring(0, filename.length() - ".ok".length());
        return marker.resolveSibling(base + suffix);
    }

    private List<RecordingTimeRange> merge(List<RecordingTimeRange> segments) {
        if (segments.isEmpty()) {
            return List.of();
        }
        segments.sort(Comparator.comparingLong(RecordingTimeRange::startTimestampUs)
                .thenComparingLong(RecordingTimeRange::endTimestampUs));
        List<RecordingTimeRange> merged = new ArrayList<>();
        long start = segments.getFirst().startTimestampUs();
        long end = segments.getFirst().endTimestampUs();
        for (int index = 1; index < segments.size(); index++) {
            RecordingTimeRange candidate = segments.get(index);
            long mergeBoundary = end > Long.MAX_VALUE - mergeToleranceUs
                    ? Long.MAX_VALUE
                    : end + mergeToleranceUs;
            if (candidate.startTimestampUs() <= mergeBoundary) {
                end = Math.max(end, candidate.endTimestampUs());
            } else {
                merged.add(new RecordingTimeRange(start, end));
                start = candidate.startTimestampUs();
                end = candidate.endTimestampUs();
            }
        }
        merged.add(new RecordingTimeRange(start, end));
        return List.copyOf(merged);
    }
}
