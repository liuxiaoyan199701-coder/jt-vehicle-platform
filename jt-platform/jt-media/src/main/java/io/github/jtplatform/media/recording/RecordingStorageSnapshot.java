package io.github.jtplatform.media.recording;

public record RecordingStorageSnapshot(long occupiedBytes, long usableBytes, long totalBytes) {
    public RecordingStorageSnapshot {
        if (occupiedBytes < 0 || usableBytes < 0 || totalBytes < 0) {
            throw new IllegalArgumentException("storage values must not be negative");
        }
    }
}
