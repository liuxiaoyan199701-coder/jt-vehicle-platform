package io.github.jtplatform.media.ingest;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.media.protocol.Jt1078Header;
import java.util.Arrays;
import java.util.Objects;

public record ReassembledPacket(StreamKey streamKey, Jt1078Header header, byte[] payload) {
    public ReassembledPacket {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(header, "header");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
