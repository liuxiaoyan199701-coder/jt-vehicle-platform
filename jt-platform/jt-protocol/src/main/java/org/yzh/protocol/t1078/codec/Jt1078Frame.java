package org.yzh.protocol.t1078.codec;

import java.util.Arrays;
import java.util.Objects;

public record Jt1078Frame(
        String deviceId,
        int channel,
        int dataType,
        int payloadType,
        long timestamp,
        int lastIFrameInterval,
        int lastFrameInterval,
        byte[] payload) {

    public Jt1078Frame {
        new Jt1078PacketHeader(
                payloadType,
                true,
                0,
                deviceId,
                channel,
                dataType,
                Jt1078FragmentFlag.ATOMIC,
                timestamp,
                lastIFrameInterval,
                lastFrameInterval);
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
