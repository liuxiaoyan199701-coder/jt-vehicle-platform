package io.github.jtplatform.api.recording;

import java.time.Instant;

public record RecordingRangeResponse(Instant startTime, Instant endTime) {
}
