package io.github.jtplatform.simulator.media;

public record PreviewSettings(int width, int height, int frameRate, int jpegQuality) {
    public PreviewSettings {
        if (width < 16 || height < 16) {
            throw new IllegalArgumentException("Preview dimensions must be at least 16x16");
        }
        if (frameRate < 1 || frameRate > 30) {
            throw new IllegalArgumentException("frameRate must be in range 1..30");
        }
        if (jpegQuality < 2 || jpegQuality > 31) {
            throw new IllegalArgumentException("jpegQuality must be in range 2..31");
        }
    }
}
