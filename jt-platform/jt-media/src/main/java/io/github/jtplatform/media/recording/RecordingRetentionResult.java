package io.github.jtplatform.media.recording;

public record RecordingRetentionResult(
        long occupiedBytesBefore,
        long occupiedBytesAfter,
        int removedSegments,
        long removedBytes) {

    public RecordingRetentionResult {
        if (occupiedBytesBefore < 0 || occupiedBytesAfter < 0
                || removedSegments < 0 || removedBytes < 0) {
            throw new IllegalArgumentException("retention result values must not be negative");
        }
    }
}
