package io.github.jtplatform.media.recording;

import io.github.jtplatform.media.config.RecordingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RecordingStorageMetrics {
    private final Path root;

    public RecordingStorageMetrics(RecordingProperties properties) {
        Objects.requireNonNull(properties, "properties").validate();
        this.root = properties.getRoot().toAbsolutePath().normalize();
    }

    public RecordingStorageSnapshot snapshot() throws IOException {
        Path probe = nearestExistingPath(root);
        var store = Files.getFileStore(probe);
        return new RecordingStorageSnapshot(
                RecordingRetentionService.occupiedBytes(root),
                store.getUsableSpace(),
                store.getTotalSpace());
    }

    private static Path nearestExistingPath(Path path) throws IOException {
        Path candidate = path;
        while (candidate != null && !Files.exists(candidate)) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IOException("No existing path is available for recording storage metrics");
        }
        return candidate;
    }
}
