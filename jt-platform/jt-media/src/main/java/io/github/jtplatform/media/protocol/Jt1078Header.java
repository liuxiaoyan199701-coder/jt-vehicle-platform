package io.github.jtplatform.media.protocol;

import java.util.Objects;

public record Jt1078Header(
        int version,
        int payloadType,
        int sequence,
        String deviceId,
        int channel,
        int dataType,
        FragmentFlag fragmentFlag,
        long timestamp,
        int lastIFrameInterval,
        int lastFrameInterval,
        int bodyLength) {

    public Jt1078Header {
        if (version < 0 || version > 255) {
            throw new IllegalArgumentException("version must be an unsigned byte");
        }
        if (payloadType < 0 || payloadType > 127) {
            throw new IllegalArgumentException("payloadType must be in range 0..127");
        }
        if (sequence < 0 || sequence > 0xffff) {
            throw new IllegalArgumentException("sequence must be in range 0..65535");
        }
        deviceId = Objects.requireNonNull(deviceId, "deviceId");
        if (deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        if (channel < 1 || channel > 255) {
            throw new IllegalArgumentException("channel must be in range 1..255");
        }
        if (dataType < Jt1078Constants.VIDEO_I_FRAME || dataType > Jt1078Constants.TRANSPARENT_DATA) {
            throw new IllegalArgumentException("Unsupported JT/T 1078 data type: " + dataType);
        }
        Objects.requireNonNull(fragmentFlag, "fragmentFlag");
        if (bodyLength < 0 || bodyLength > 0xffff) {
            throw new IllegalArgumentException("bodyLength must be in range 0..65535");
        }
    }

    public boolean video() {
        return dataType <= Jt1078Constants.VIDEO_B_FRAME;
    }
}
