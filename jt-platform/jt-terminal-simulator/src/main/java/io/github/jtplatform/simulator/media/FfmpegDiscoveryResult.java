package io.github.jtplatform.simulator.media;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FfmpegDiscoveryResult(
        Optional<Path> ffmpeg,
        Optional<Path> ffprobe,
        List<String> checkedSources) {

    public FfmpegDiscoveryResult {
        ffmpeg = normalize(ffmpeg, "ffmpeg");
        ffprobe = normalize(ffprobe, "ffprobe");
        checkedSources = List.copyOf(Objects.requireNonNull(checkedSources, "checkedSources"));
    }

    public String diagnostics() {
        return String.join(System.lineSeparator(), checkedSources);
    }

    public String missingExecutableMessage() {
        String checks = diagnostics();
        return "未找到 FFmpeg 可执行文件。"
                + (checks.isBlank() ? "" : System.lineSeparator() + checks)
                + System.lineSeparator()
                + "请点击“浏览...”选择 ffmpeg.exe（也可选择同目录的 ffprobe.exe），"
                + "或将 FFmpeg 的 bin 目录加入 PATH 后重启模拟器。";
    }

    private static Optional<Path> normalize(Optional<Path> value, String name) {
        return Objects.requireNonNull(value, name)
                .map(path -> path.toAbsolutePath().normalize());
    }
}
