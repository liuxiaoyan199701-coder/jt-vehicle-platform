package io.github.jtplatform.media.recording;

import io.github.jtplatform.common.model.StreamKey;
import java.nio.file.Path;
import java.util.Objects;

public record RecordingSegmentMetadata(
        StreamKey streamKey,
        long startTimestampUs,
        long endTimestampUs,
        long frameCount,
        long keyFrameCount,
        long dataBytes,
        long indexBytes,
        Path dataPath,
        Path indexPath,
        Path markerPath) {

    public RecordingSegmentMetadata {
        Objects.requireNonNull(streamKey, "streamKey");
        if (startTimestampUs < 0 || endTimestampUs < startTimestampUs
                || frameCount <= 0 || keyFrameCount < 0
                || dataBytes <= 0 || indexBytes <= 0) {
            throw new IllegalArgumentException("invalid recording segment metadata");
        }
        dataPath = absolute(dataPath, "dataPath");
        indexPath = absolute(indexPath, "indexPath");
        markerPath = absolute(markerPath, "markerPath");
    }

    private static Path absolute(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
