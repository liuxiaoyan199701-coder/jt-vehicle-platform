package io.github.jtplatform.media.recording;

import java.nio.file.Path;
import java.util.Objects;

public record RecordingExportResult(
        Path outputFile,
        long sizeBytes,
        long requestedStartTimestampUs,
        long requestedEndTimestampUs) {

    public RecordingExportResult {
        outputFile = Objects.requireNonNull(outputFile, "outputFile")
                .toAbsolutePath()
                .normalize();
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (requestedStartTimestampUs < 0
                || requestedEndTimestampUs < requestedStartTimestampUs) {
            throw new IllegalArgumentException("invalid requested recording range");
        }
    }
}
