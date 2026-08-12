package io.github.jtplatform.simulator.media;

import java.util.Arrays;
import java.util.Objects;

public record MediaFrame(MediaFrameType type, long timestampMillis, byte[] payload) {
    public MediaFrame {
        type = Objects.requireNonNull(type, "type");
        if (timestampMillis < 0) {
            throw new IllegalArgumentException("timestampMillis must not be negative");
        }
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        if (payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
    }

    public static MediaFrame video(VideoAccessUnit accessUnit, long timestampMillis) {
        Objects.requireNonNull(accessUnit, "accessUnit");
        return new MediaFrame(accessUnit.keyFrame() ? MediaFrameType.VIDEO_I : MediaFrameType.VIDEO_P,
                timestampMillis, accessUnit.payload());
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public int payloadLength() {
        return payload.length;
    }
}
