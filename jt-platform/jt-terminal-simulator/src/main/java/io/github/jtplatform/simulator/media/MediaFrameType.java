package io.github.jtplatform.simulator.media;

public enum MediaFrameType {
    VIDEO_I,
    VIDEO_P,
    AUDIO;

    public boolean video() {
        return this != AUDIO;
    }

    public boolean keyFrame() {
        return this == VIDEO_I;
    }
}
