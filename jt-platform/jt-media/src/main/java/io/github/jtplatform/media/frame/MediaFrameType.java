package io.github.jtplatform.media.frame;

public enum MediaFrameType {
    VIDEO_KEY(0x00),
    VIDEO_DELTA(0x01),
    VIDEO_B(0x02),
    AUDIO(0x03),
    TRANSPARENT(0x04),
    SPS(0xf0),
    PPS(0xf1),
    AUDIO_CONFIG(0xf2),
    VPS(0xf3);

    private final int wireValue;

    MediaFrameType(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public boolean parameterSet() {
        return this == SPS || this == PPS || this == VPS || this == AUDIO_CONFIG;
    }
}
