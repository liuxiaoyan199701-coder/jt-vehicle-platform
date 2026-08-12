package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.media.DirectShowDevices;
import io.github.jtplatform.simulator.media.FfmpegCapabilities;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FfmpegProbeResult(
        Optional<Path> executable,
        FfmpegCapabilities capabilities,
        DirectShowDevices devices,
        String summary,
        String diagnostics) {

    public FfmpegProbeResult {
        executable = Objects.requireNonNull(executable, "executable");
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        devices = Objects.requireNonNull(devices, "devices");
        summary = normalize(summary);
        diagnostics = diagnostics == null ? "" : diagnostics.strip();
    }

    public static FfmpegProbeResult unavailable(String summary, String diagnostics) {
        return new FfmpegProbeResult(
                Optional.empty(),
                new FfmpegCapabilities("", false, false, false, diagnostics),
                new DirectShowDevices(List.of(), List.of(), diagnostics),
                summary,
                diagnostics);
    }

    public boolean supported() {
        return executable.isPresent() && capabilities.supported();
    }

    private static String normalize(String value) {
        String normalized = Objects.requireNonNull(value, "summary").trim();
        return normalized.isEmpty() ? "FFmpeg 状态未知" : normalized;
    }
}
