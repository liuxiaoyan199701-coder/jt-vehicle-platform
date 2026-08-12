package io.github.jtplatform.media.recording;

import io.github.jtplatform.media.config.RecordingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RecordingRetentionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingRetentionService.class);
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private final Path root;
    private final int retentionDays;
    private final long maxStorageBytes;
    private final Clock clock;

    public RecordingRetentionService(RecordingProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties").validate();
        this.root = properties.getRoot().toAbsolutePath().normalize();
        this.retentionDays = properties.getRetentionDays();
        this.maxStorageBytes = properties.getMaxBytes();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RecordingRetentionResult cleanup() throws IOException {
        if (!Files.isDirectory(root)) {
            return new RecordingRetentionResult(0, 0, 0, 0);
        }
        long occupiedBefore = occupiedBytes(root);
        List<SegmentFamily> segments = committedSegments();
        Set<Path> removed = new HashSet<>();
        int removedSegments = 0;
        long removedBytes = 0;
        long occupied = occupiedBefore;

        if (retentionDays > 0) {
            long cutoffUs = cutoffTimestampUs();
            for (SegmentFamily segment : segments) {
                if (segment.endTimestampUs() >= cutoffUs) {
                    continue;
                }
                if (delete(segment)) {
                    removed.add(segment.marker());
                    removedSegments++;
                    removedBytes += segment.bytes();
                    occupied = Math.max(0, occupied - segment.bytes());
                }
            }
        }

        if (maxStorageBytes > 0 && occupied >= maxStorageBytes) {
            for (SegmentFamily segment : segments) {
                if (occupied < maxStorageBytes) {
                    break;
                }
                if (removed.contains(segment.marker())) {
                    continue;
                }
                if (delete(segment)) {
                    removedSegments++;
                    removedBytes += segment.bytes();
                    occupied = Math.max(0, occupied - segment.bytes());
                }
            }
        }

        long occupiedAfter = occupiedBytes(root);
        return new RecordingRetentionResult(
                occupiedBefore, occupiedAfter, removedSegments, removedBytes);
    }

    private List<SegmentFamily> committedSegments() throws IOException {
        List<SegmentFamily> segments = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path marker : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ok"))
                    .toList()) {
                RecordingSegmentInspection inspection = RecordingSegmentInspector.inspect(marker);
                if (inspection.status() != RecordingSegmentStatus.COMMITTED) {
                    LOGGER.warn("Skipping unusable recording segment during retention {}: {}",
                            marker, inspection.reason());
                    continue;
                }
                Path data = sibling(marker, ".jtr");
                Path index = sibling(marker, ".jti");
                long bytes = Files.size(data) + Files.size(index) + Files.size(marker);
                segments.add(new SegmentFamily(
                        data, index, marker, inspection.endTimestampUs(), bytes));
            }
        }
        segments.sort(Comparator.comparingLong(SegmentFamily::endTimestampUs)
                .thenComparing(segment -> segment.marker().toString()));
        return segments;
    }

    private long cutoffTimestampUs() {
        long retentionUs;
        try {
            retentionUs = Duration.ofDays(retentionDays).toNanos() / 1_000L;
        } catch (ArithmeticException tooLarge) {
            return Long.MIN_VALUE;
        }
        Instant now = clock.instant();
        long nowSeconds = now.getEpochSecond();
        int nowNanos = now.getNano();
        long nowUs;
        try {
            nowUs = Math.addExact(Math.multiplyExact(nowSeconds, MICROS_PER_SECOND), nowNanos / 1_000L);
        } catch (ArithmeticException tooLarge) {
            return nowSeconds < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return nowUs < Long.MIN_VALUE + retentionUs ? Long.MIN_VALUE : nowUs - retentionUs;
    }

    private boolean delete(SegmentFamily segment) {
        try {
            Files.deleteIfExists(segment.data());
            Files.deleteIfExists(segment.index());
            Files.deleteIfExists(segment.marker());
            LOGGER.info("Deleted recording segment {} ({} bytes) due to retention policy",
                    segment.marker(), segment.bytes());
            return true;
        } catch (IOException failure) {
            LOGGER.error("Unable to delete recording segment {}", segment.marker(), failure);
            return false;
        }
    }

    static long occupiedBytes(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(RecordingRetentionService::sizeOrZero)
                    .sum();
        }
    }

    private static long sizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static Path sibling(Path marker, String extension) {
        String filename = marker.getFileName().toString();
        return marker.resolveSibling(filename.substring(0, filename.length() - 3) + extension);
    }

    private record SegmentFamily(
            Path data, Path index, Path marker, long endTimestampUs, long bytes) {
    }
}
