package io.github.jtplatform.simulator.media;

public record FfmpegCapabilities(
        String version,
        boolean directShow,
        boolean libx264,
        boolean pcmAlaw,
        String diagnostics) {

    public FfmpegCapabilities {
        version = version == null ? "" : version.trim();
        diagnostics = diagnostics == null ? "" : diagnostics;
    }

    public boolean supported() {
        return directShow && libx264 && pcmAlaw;
    }
}
