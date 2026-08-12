package io.github.jtplatform.common.model;

import java.util.Objects;

public record StreamKey(String deviceId, int channel, StreamKind streamKind) {
    public StreamKey {
        deviceId = Objects.requireNonNull(deviceId, "deviceId").trim();
        if (deviceId.isEmpty()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        if (channel < 1 || channel > 255) {
            throw new IllegalArgumentException("channel must be in range 1..255");
        }
        Objects.requireNonNull(streamKind, "streamKind");
    }

    public String externalId() {
        return deviceId + ':' + channel + ':' + streamKind.wireValue();
    }
}
