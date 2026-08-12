package io.github.jtplatform.media.recording;

import io.github.jtplatform.common.model.StreamKey;
import java.util.Objects;

public record RecordingPlaybackRequest(
        StreamKey streamKey,
        long startTimestampUs,
        long endTimestampUs) {

    public RecordingPlaybackRequest {
        Objects.requireNonNull(streamKey, "streamKey");
        if (startTimestampUs < 0) {
            throw new IllegalArgumentException("startTimestampUs must not be negative");
        }
        if (endTimestampUs < startTimestampUs) {
            throw new IllegalArgumentException("endTimestampUs must not precede startTimestampUs");
        }
    }
}
