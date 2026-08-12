package io.github.jtplatform.api.stream;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record OpenStreamRequest(
        @NotBlank String deviceId,
        @Min(1) @Max(255) int channel,
        @NotNull StreamKind streamKind,
        Instant startTime,
        Instant endTime) {
    public OpenStreamRequest(String deviceId, int channel, StreamKind streamKind) {
        this(deviceId, channel, streamKind, null, null);
    }

    public OpenStreamRequest {
        if (streamKind == StreamKind.PLAYBACK) {
            if (startTime == null || endTime == null) {
                throw new IllegalArgumentException(
                        "startTime and endTime are required for playback");
            }
            if (startTime.isBefore(Instant.EPOCH) || !endTime.isAfter(startTime)) {
                throw new IllegalArgumentException(
                        "playback endTime must be after startTime and both must be non-negative");
            }
        } else if (startTime != null || endTime != null) {
            throw new IllegalArgumentException(
                    "startTime and endTime are only valid for playback");
        }
    }

    public StreamKey streamKey() {
        return new StreamKey(deviceId, channel, streamKind);
    }
}
