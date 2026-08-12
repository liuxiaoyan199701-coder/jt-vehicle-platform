package io.github.jtplatform.media.protocol;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import java.util.Arrays;
import java.util.Objects;

public record RtpPacket(Jt1078Header header, StreamKind streamKind, byte[] payload) {
    public RtpPacket {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(streamKind, "streamKind");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        if (payload.length != header.bodyLength()) {
            throw new IllegalArgumentException("payload length does not match header bodyLength");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public StreamKey streamKey() {
        return new StreamKey(header.deviceId(), header.channel(), streamKind);
    }
}
