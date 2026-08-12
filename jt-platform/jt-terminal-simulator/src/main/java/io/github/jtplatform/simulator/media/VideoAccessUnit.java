package io.github.jtplatform.simulator.media;

import java.util.Arrays;
import java.util.Objects;

public record VideoAccessUnit(boolean keyFrame, byte[] payload) {
    public VideoAccessUnit {
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
