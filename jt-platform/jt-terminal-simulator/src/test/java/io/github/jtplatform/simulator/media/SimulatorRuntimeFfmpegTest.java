package io.github.jtplatform.simulator.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.ConfigStore;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.ui.FfmpegProbeResult;
import io.github.jtplatform.simulator.ui.SimulatorRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulatorRuntimeFfmpegTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsTheResolvedAbsolutePathAfterSuccessfulDetection() throws Exception {
        Path executable = Files.createFile(temporaryDirectory.resolve("ffmpeg.exe"));
        ConfigStore store = new ConfigStore(temporaryDirectory.resolve("config"));
        FfmpegDiscovery discovery = supportedDiscovery();

        try (SimulatorRuntime runtime = new SimulatorRuntime(store, discovery)) {
            runtime.loadConfig();

            FfmpegProbeResult result = runtime.detectFfmpeg(executable.toString())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertTrue(result.supported());
            assertEquals(executable.toAbsolutePath().normalize().toString(),
                    store.load().ffmpegPath());
            assertTrue(runtime.recentLogs().stream()
                    .anyMatch(entry -> entry.message().contains("Resolved FFmpeg path saved")));
        }
    }

    @Test
    void keepsTheConcreteBackendReasonWhenPreviewStartupFails() throws Exception {
        ConfigStore store = new ConfigStore(temporaryDirectory.resolve("preview-config"));
        try (SimulatorRuntime runtime = new SimulatorRuntime(store, supportedDiscovery())) {
            SimulatorConfig defaults = runtime.loadConfig();
            SimulatorConfig invalidPath = copyCapture(
                    defaults, "bad" + (char) 0 + "path", "Camera One", "Microphone One");

            CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    CompletionException.class,
                    () -> runtime.startPreview(invalidPath).toCompletableFuture().join());

            assertTrue(failure.getCause().getMessage().contains("配置路径格式无效"));
            assertTrue(failure.getCause().getMessage().contains("浏览"));
        }
    }

    private FfmpegDiscovery supportedDiscovery() {
        return new FfmpegDiscovery((command, timeout) -> {
            if (command.contains("-version")) {
                return new FfmpegDiscovery.CommandResult(0, "ffmpeg version test\n");
            }
            if (command.contains("-devices")) {
                return new FfmpegDiscovery.CommandResult(0, " D  dshow DirectShow capture\n");
            }
            if (command.contains("-encoders")) {
                return new FfmpegDiscovery.CommandResult(
                        0, " V....D libx264 H.264\n A....D pcm_alaw PCM A-law\n");
            }
            return new FfmpegDiscovery.CommandResult(
                    0, "\"Camera One\" (video)\n\"Microphone One\" (audio)\n");
        }, Map.of());
    }

    private static SimulatorConfig copyCapture(
            SimulatorConfig source,
            String ffmpegPath,
            String camera,
            String microphone) {
        return new SimulatorConfig(
                source.signalHost(), source.signalPort(), source.version(), source.mobileNo(),
                source.deviceId(), source.channel(), source.registration(), ffmpegPath, camera,
                microphone, source.mainProfile(), source.subProfile(), source.previewWidth(),
                source.previewHeight(), source.previewFps(), source.maxPayloadBytes(),
                source.trip());
    }
}
