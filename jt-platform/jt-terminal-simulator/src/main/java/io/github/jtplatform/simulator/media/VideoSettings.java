package io.github.jtplatform.simulator.media;

public record VideoSettings(int width, int height, int frameRate, int bitrateKbps, int gopSeconds) {
    public VideoSettings {
        if (width < 16 || height < 16) {
            throw new IllegalArgumentException("Video dimensions must be at least 16x16");
        }
        if (frameRate < 1 || frameRate > 120) {
            throw new IllegalArgumentException("frameRate must be in range 1..120");
        }
        if (bitrateKbps < 32) {
            throw new IllegalArgumentException("bitrateKbps must be at least 32");
        }
        if (gopSeconds < 1 || gopSeconds > 60) {
            throw new IllegalArgumentException("gopSeconds must be in range 1..60");
        }
    }

    public int gopFrames() {
        return Math.multiplyExact(frameRate, gopSeconds);
    }
}
