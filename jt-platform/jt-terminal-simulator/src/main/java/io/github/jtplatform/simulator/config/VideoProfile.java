package io.github.jtplatform.simulator.config;

public record VideoProfile(
        int width,
        int height,
        int frameRate,
        int bitrateKbps,
        int gopSeconds) {

    public VideoProfile {
        if (width < 160 || width > 7_680 || (width & 1) != 0) {
            throw new IllegalArgumentException("width must be an even value in range 160..7680");
        }
        if (height < 120 || height > 4_320 || (height & 1) != 0) {
            throw new IllegalArgumentException("height must be an even value in range 120..4320");
        }
        if (frameRate < 1 || frameRate > 60) {
            throw new IllegalArgumentException("frameRate must be in range 1..60");
        }
        if (bitrateKbps < 64 || bitrateKbps > 50_000) {
            throw new IllegalArgumentException("bitrateKbps must be in range 64..50000");
        }
        if (gopSeconds < 1 || gopSeconds > 10) {
            throw new IllegalArgumentException("gopSeconds must be in range 1..10");
        }
    }

    public static VideoProfile defaultMain() {
        return new VideoProfile(1_280, 720, 25, 2_000, 2);
    }

    public static VideoProfile defaultSub() {
        return new VideoProfile(640, 360, 15, 512, 2);
    }
}
