package org.yzh.protocol.t1078.codec;

import java.util.Objects;

public record Jt1078PacketHeader(
        int payloadType,
        boolean marker,
        int sequence,
        String deviceId,
        int channel,
        int dataType,
        Jt1078FragmentFlag fragmentFlag,
        long timestamp,
        int lastIFrameInterval,
        int lastFrameInterval) {

    public Jt1078PacketHeader {
        validatePayloadType(payloadType);
        validateSequence(sequence);
        validateDeviceId(deviceId);
        validateChannel(channel);
        validateDataType(dataType);
        Objects.requireNonNull(fragmentFlag, "fragmentFlag");
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }
        validateUnsignedShort(lastIFrameInterval, "lastIFrameInterval");
        validateUnsignedShort(lastFrameInterval, "lastFrameInterval");
    }

    public boolean video() {
        return dataType <= Jt1078WireConstants.VIDEO_B_FRAME;
    }

    public boolean audio() {
        return dataType == Jt1078WireConstants.AUDIO_FRAME;
    }

    public int encodedLength() {
        if (video()) {
            return Jt1078WireConstants.VIDEO_HEADER_LENGTH;
        }
        if (audio()) {
            return Jt1078WireConstants.AUDIO_HEADER_LENGTH;
        }
        return Jt1078WireConstants.TRANSPARENT_HEADER_LENGTH;
    }

    static void validatePayloadType(int payloadType) {
        if (payloadType < 0 || payloadType > 0x7f) {
            throw new IllegalArgumentException("payloadType must be in range 0..127");
        }
    }

    static void validateSequence(int sequence) {
        validateUnsignedShort(sequence, "sequence");
    }

    static void validateDeviceId(String deviceId) {
        Objects.requireNonNull(deviceId, "deviceId");
        // 2019 版终端的手机号是 BCD[10]（20 位），同样可以作为 1078 的 SIM 来源
        if (!deviceId.matches("\\d{1,20}")) {
            throw new IllegalArgumentException(
                    "deviceId must contain 1..20 decimal digits");
        }
    }

    static void validateChannel(int channel) {
        if (channel < 1 || channel > 0xff) {
            throw new IllegalArgumentException("channel must be in range 1..255");
        }
    }

    static void validateDataType(int dataType) {
        if (dataType < Jt1078WireConstants.VIDEO_I_FRAME
                || dataType > Jt1078WireConstants.TRANSPARENT_DATA) {
            throw new IllegalArgumentException(
                    "Unsupported JT/T 1078 data type: " + dataType);
        }
    }

    private static void validateUnsignedShort(int value, String name) {
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException(name + " must be in range 0..65535");
        }
    }
}
