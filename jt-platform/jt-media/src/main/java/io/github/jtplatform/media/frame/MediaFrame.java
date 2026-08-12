package io.github.jtplatform.media.frame;

import io.github.jtplatform.common.model.StreamKey;
import java.util.Arrays;
import java.util.Objects;

public record MediaFrame(
        StreamKey streamKey,
        MediaFrameType type,
        MediaCodec codec,
        long timestamp,
        byte[] payload) {

    public MediaFrame {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(codec, "codec");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
