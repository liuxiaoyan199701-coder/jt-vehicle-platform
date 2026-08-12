package io.github.jtplatform.common.model;

public record RecordingTimeRange(long startTimestampUs, long endTimestampUs) {
    public RecordingTimeRange {
        if (startTimestampUs < 0) {
            throw new IllegalArgumentException("startTimestampUs must not be negative");
        }
        if (endTimestampUs < startTimestampUs) {
            throw new IllegalArgumentException("endTimestampUs must not precede startTimestampUs");
        }
    }
}
