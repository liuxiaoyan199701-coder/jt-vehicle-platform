package io.github.jtplatform.media.recording;

import java.nio.file.Path;
import java.util.Objects;

record RecordingSegmentReference(
        long startTimestampUs,
        long endTimestampUs,
        long frameCount,
        long keyFrameCount,
        Path dataPath,
        Path indexPath,
        Path markerPath) {

    RecordingSegmentReference {
        if (startTimestampUs < 0
                || endTimestampUs < startTimestampUs
                || frameCount <= 0
                || keyFrameCount < 0) {
            throw new IllegalArgumentException("invalid recording segment reference");
        }
        dataPath = absolute(dataPath, "dataPath");
        indexPath = absolute(indexPath, "indexPath");
        markerPath = absolute(markerPath, "markerPath");
    }

    private static Path absolute(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }
}
