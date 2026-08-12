package io.github.jtplatform.simulator.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegDiscoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void configuredAbsolutePathTakesPriorityOverPath() throws Exception {
        Path configured = Files.createFile(temporaryDirectory.resolve("configured-ffmpeg.exe"));
        Path pathDirectory = Files.createDirectory(temporaryDirectory.resolve("path"));
        Files.createFile(pathDirectory.resolve("ffmpeg.exe"));
        FfmpegDiscovery discovery = new FfmpegDiscovery(
                (command, timeout) -> new FfmpegDiscovery.CommandResult(0, ""),
                Map.of("Path", pathDirectory.toString()));

        assertEquals(configured.toAbsolutePath(), discovery.find(configured).orElseThrow());
    }

    @Test
    void fallsBackToCaseInsensitivePathEnvironment() throws Exception {
        Path pathDirectory = Files.createDirectory(temporaryDirectory.resolve("bin"));
        Path executable = Files.createFile(pathDirectory.resolve("ffmpeg.exe"));
        FfmpegDiscovery discovery = new FfmpegDiscovery(
                (command, timeout) -> new FfmpegDiscovery.CommandResult(0, ""),
                Map.of("Path", pathDirectory.toString()));

        assertEquals(executable.toAbsolutePath(), discovery.find(null).orElseThrow());
    }

    @Test
    void acceptsConfiguredBinDirectoryAndDiscoversTheToolchain() throws Exception {
        Path bin = Files.createDirectory(temporaryDirectory.resolve("bin"));
        Path ffmpeg = Files.createFile(bin.resolve("FFMPEG.EXE"));
        Path ffprobe = Files.createFile(bin.resolve("FFPROBE.EXE"));
        FfmpegDiscovery discovery = new FfmpegDiscovery(
                (command, timeout) -> new FfmpegDiscovery.CommandResult(0, ""), Map.of());

        FfmpegDiscoveryResult result = discovery.discover(bin.toAbsolutePath());

        assertEquals(ffmpeg.toAbsolutePath(), result.ffmpeg().orElseThrow());
        assertEquals(ffprobe.toAbsolutePath(), result.ffprobe().orElseThrow());
        assertTrue(result.diagnostics().contains("配置目录"));
    }

    @Test
    void resolvesFfmpegBesideAConfiguredFfprobe() throws Exception {
        Path bin = Files.createDirectory(temporaryDirectory.resolve("probe-bin"));
        Path ffmpeg = Files.createFile(bin.resolve("ffmpeg.exe"));
        Path ffprobe = Files.createFile(bin.resolve("ffprobe.exe"));
        FfmpegDiscovery discovery = new FfmpegDiscovery(
                (command, timeout) -> new FfmpegDiscovery.CommandResult(0, ""), Map.of());

        FfmpegDiscoveryResult result = discovery.discover(ffprobe.toAbsolutePath());

        assertEquals(ffmpeg.toAbsolutePath(), result.ffmpeg().orElseThrow());
        assertEquals(ffprobe.toAbsolutePath(), result.ffprobe().orElseThrow());
        assertTrue(result.diagnostics().contains("同目录解析 FFmpeg"));
    }

    @Test
    void reportsEveryDiscoverySourceWhenFfmpegIsMissing() throws Exception {
        Path configured = temporaryDirectory.resolve("missing-ffmpeg.exe").toAbsolutePath();
        Path pathDirectory = Files.createDirectory(temporaryDirectory.resolve("empty-path"));
        FfmpegDiscovery discovery = new FfmpegDiscovery(
                (command, timeout) -> new FfmpegDiscovery.CommandResult(0, ""),
                Map.of("PATH", pathDirectory.toString()));

        FfmpegDiscoveryResult result = discovery.discover(configured);

        assertTrue(result.ffmpeg().isEmpty());
        assertTrue(result.diagnostics().contains(configured.toString()));
        assertTrue(result.diagnostics().contains(pathDirectory.toString()));
        assertTrue(result.diagnostics().contains("已检查 1 个目录"));
        assertTrue(result.missingExecutableMessage().contains("重启模拟器"));
    }

    @Test
    void inspectsRequiredInputAndEncoders() throws Exception {
        Path executable = Files.createFile(temporaryDirectory.resolve("ffmpeg.exe"));
        FfmpegDiscovery discovery = new FfmpegDiscovery((command, timeout) -> {
            if (command.contains("-version")) {
                return new FfmpegDiscovery.CommandResult(0, "ffmpeg version 9.0-full\n");
            }
            if (command.contains("-devices")) {
                return new FfmpegDiscovery.CommandResult(0, " D  dshow          DirectShow capture\n");
            }
            return new FfmpegDiscovery.CommandResult(0,
                    " V....D libx264        H.264\n A....D pcm_alaw       PCM A-law\n");
        }, Map.of());

        FfmpegCapabilities capabilities = discovery.inspect(executable);

        assertEquals("ffmpeg version 9.0-full", capabilities.version());
        assertTrue(capabilities.directShow());
        assertTrue(capabilities.libx264());
        assertTrue(capabilities.pcmAlaw());
        assertTrue(capabilities.supported());
    }

    @Test
    void usesDeviceListingInsteadOfTheAmbiguousFormatsColumns() throws Exception {
        Path executable = Files.createFile(temporaryDirectory.resolve("ffmpeg.exe"));
        java.util.List<java.util.List<String>> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
        FfmpegDiscovery discovery = new FfmpegDiscovery((command, timeout) -> {
            commands.add(command);
            if (command.contains("-version")) {
                return new FfmpegDiscovery.CommandResult(0, "ffmpeg version 9.0-full\n");
            }
            if (command.contains("-devices")) {
                return new FfmpegDiscovery.CommandResult(0, " D  dshow          DirectShow capture\n");
            }
            return new FfmpegDiscovery.CommandResult(0,
                    " V....D libx264 H.264\n A....D pcm_alaw PCM A-law\n");
        }, Map.of());

        FfmpegCapabilities capabilities = discovery.inspect(executable);

        assertTrue(capabilities.directShow());
        assertTrue(commands.stream().anyMatch(command -> command.contains("-devices")));
        assertFalse(commands.stream().anyMatch(command -> command.contains("-formats")));
    }

    @Test
    void reportsMissingCapabilityWithoutHidingDiagnostics() throws Exception {
        Path executable = Files.createFile(temporaryDirectory.resolve("ffmpeg.exe"));
        FfmpegDiscovery discovery = new FfmpegDiscovery((command, timeout) ->
                new FfmpegDiscovery.CommandResult(0, command.contains("-devices")
                        ? " D  dshow DirectShow\n" : " V....D libx264 H.264\n"), Map.of());

        FfmpegCapabilities capabilities = discovery.inspect(executable);

        assertFalse(capabilities.pcmAlaw());
        assertFalse(capabilities.supported());
        assertTrue(capabilities.diagnostics().contains("libx264"));
    }

    @Test
    void parsesUnicodeDevicesAndAlternativeNames() {
        String output = """
                [dshow @ 0001] DirectShow video devices
                [dshow @ 0001]  "集成摄像头" (video)
                [dshow @ 0001]    Alternative name "@device_pnp_camera"
                [dshow @ 0001]  "麦克风阵列 (USB)" (audio)
                [dshow @ 0001]    Alternative name "@device_cm_audio"
                """;

        DirectShowDevices devices = FfmpegDiscovery.parseDirectShowDevices(output);

        assertEquals(1, devices.videoDevices().size());
        assertEquals("集成摄像头", devices.videoDevices().getFirst().name());
        assertEquals("@device_pnp_camera", devices.videoDevices().getFirst().alternativeName());
        assertEquals("麦克风阵列 (USB)", devices.audioDevices().getFirst().name());
        assertTrue(devices.diagnostics().contains("DirectShow video devices"));
    }
}
