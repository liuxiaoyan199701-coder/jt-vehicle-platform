package io.github.jtplatform.simulator.media;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FfmpegDiscovery {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern DEVICE = Pattern.compile("\\\"([^\\\"]+)\\\"\\s+\\((video|audio)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTERNATIVE = Pattern.compile(
            "Alternative name\\s+\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);

    private final CommandExecutor executor;
    private final Map<String, String> environment;

    public FfmpegDiscovery() {
        this(new SystemCommandExecutor(), System.getenv());
    }

    FfmpegDiscovery(CommandExecutor executor, Map<String, String> environment) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.environment = Map.copyOf(environment);
    }

    public Optional<Path> find(Path configuredExecutable) {
        return discover(configuredExecutable).ffmpeg();
    }

    public FfmpegDiscoveryResult discover(Path configuredLocation) {
        List<String> checkedSources = new ArrayList<>();
        FfmpegDiscoveryResult configured = discoverConfigured(configuredLocation, checkedSources);
        if (configured.ffmpeg().isPresent()) {
            return configured;
        }

        String pathValue = environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
        int pathDirectories = 0;
        for (String directory : pathValue.split(Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            pathDirectories++;
            Path root;
            try {
                root = Path.of(stripQuotes(directory.trim())).toAbsolutePath().normalize();
            } catch (InvalidPathException invalidPath) {
                checkedSources.add("PATH 项格式无效: " + directory.trim());
                continue;
            }
            Optional<Path> ffmpeg = findNamedExecutable(root, "ffmpeg.exe", "ffmpeg");
            Optional<Path> ffprobe = findNamedExecutable(root, "ffprobe.exe", "ffprobe");
            if (ffmpeg.isPresent()) {
                checkedSources.add("PATH 目录: " + root + "（已找到 FFmpeg，"
                        + companionStatus(ffprobe) + "）");
                return new FfmpegDiscoveryResult(ffmpeg, ffprobe, checkedSources);
            }
            if (ffprobe.isPresent()) {
                checkedSources.add("PATH 目录: " + root + "（仅找到 ffprobe，缺少 ffmpeg）");
            } else {
                checkedSources.add("PATH 目录: " + root + "（未找到 FFmpeg/ffprobe）");
            }
        }
        if (pathDirectories == 0) {
            checkedSources.add("PATH: 未配置可搜索目录");
        } else {
            checkedSources.add("PATH: 已检查 " + pathDirectories + " 个目录，未找到 FFmpeg");
        }
        return new FfmpegDiscoveryResult(Optional.empty(), configured.ffprobe(), checkedSources);
    }

    public FfmpegCapabilities inspect(Path executable) throws IOException, InterruptedException {
        Path checked = requireExecutable(executable);
        CommandResult version = executor.execute(List.of(checked.toString(), "-hide_banner", "-version"),
                COMMAND_TIMEOUT);
        CommandResult devices = executor.execute(List.of(checked.toString(), "-hide_banner", "-devices"),
                COMMAND_TIMEOUT);
        CommandResult encoders = executor.execute(List.of(checked.toString(), "-hide_banner", "-encoders"),
                COMMAND_TIMEOUT);
        String diagnostics = String.join(System.lineSeparator(), version.output(), devices.output(), encoders.output());
        return new FfmpegCapabilities(
                firstNonBlankLine(version.output()),
                version.exitCode() == 0 && devices.exitCode() == 0 && supportsDevice(devices.output(), "dshow"),
                version.exitCode() == 0 && encoders.exitCode() == 0 && supportsEncoder(encoders.output(), "libx264"),
                version.exitCode() == 0 && encoders.exitCode() == 0 && supportsEncoder(encoders.output(), "pcm_alaw"),
                diagnostics);
    }

    public DirectShowDevices enumerateDirectShow(Path executable) throws IOException, InterruptedException {
        Path checked = requireExecutable(executable);
        CommandResult result = executor.execute(List.of(
                checked.toString(), "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"),
                COMMAND_TIMEOUT);
        return parseDirectShowDevices(result.output());
    }

    public static DirectShowDevices parseDirectShowDevices(String diagnostics) {
        String output = diagnostics == null ? "" : diagnostics;
        List<DirectShowDevice> devices = new ArrayList<>();
        int lastDevice = -1;
        for (String line : output.lines().toList()) {
            Matcher deviceMatcher = DEVICE.matcher(line);
            if (deviceMatcher.find()) {
                DirectShowDevice.Type type = deviceMatcher.group(2).equalsIgnoreCase("video")
                        ? DirectShowDevice.Type.VIDEO : DirectShowDevice.Type.AUDIO;
                devices.add(new DirectShowDevice(type, deviceMatcher.group(1), ""));
                lastDevice = devices.size() - 1;
                continue;
            }
            Matcher alternativeMatcher = ALTERNATIVE.matcher(line);
            if (lastDevice >= 0 && alternativeMatcher.find()) {
                DirectShowDevice previous = devices.get(lastDevice);
                devices.set(lastDevice, new DirectShowDevice(
                        previous.type(), previous.name(), alternativeMatcher.group(1)));
            }
        }
        List<DirectShowDevice> video = devices.stream()
                .filter(device -> device.type() == DirectShowDevice.Type.VIDEO)
                .toList();
        List<DirectShowDevice> audio = devices.stream()
                .filter(device -> device.type() == DirectShowDevice.Type.AUDIO)
                .toList();
        return new DirectShowDevices(video, audio, output);
    }

    private static boolean supportsDevice(String output, String name) {
        for (String line : output.lines().toList()) {
            String[] columns = line.trim().split("\\s+", 3);
            if (columns.length >= 2 && columns[0].toUpperCase(Locale.ROOT).contains("D")
                    && columns[1].equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean supportsEncoder(String output, String name) {
        for (String line : output.lines().toList()) {
            String[] columns = line.trim().split("\\s+", 3);
            if (columns.length >= 2 && columns[1].equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlankLine(String value) {
        return value.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse("");
    }

    private static FfmpegDiscoveryResult discoverConfigured(
            Path configuredLocation,
            List<String> checkedSources) {
        if (configuredLocation == null) {
            checkedSources.add("配置路径: 未设置");
            return new FfmpegDiscoveryResult(Optional.empty(), Optional.empty(), checkedSources);
        }
        if (!configuredLocation.isAbsolute()) {
            checkedSources.add("配置路径不是绝对路径: " + configuredLocation);
            return new FfmpegDiscoveryResult(Optional.empty(), Optional.empty(), checkedSources);
        }

        Path configured = configuredLocation.toAbsolutePath().normalize();
        if (Files.isDirectory(configured)) {
            Optional<Path> ffmpeg = findNamedExecutable(configured, "ffmpeg.exe", "ffmpeg");
            Optional<Path> ffprobe = findNamedExecutable(configured, "ffprobe.exe", "ffprobe");
            checkedSources.add("配置目录: " + configured + (ffmpeg.isPresent()
                    ? "（已找到 FFmpeg，" + companionStatus(ffprobe) + "）"
                    : "（未找到 ffmpeg.exe/ffmpeg，" + companionStatus(ffprobe) + "）"));
            return new FfmpegDiscoveryResult(ffmpeg, ffprobe, checkedSources);
        }
        if (!Files.isRegularFile(configured)) {
            checkedSources.add("配置路径不存在或不是文件: " + configured);
            return new FfmpegDiscoveryResult(Optional.empty(), Optional.empty(), checkedSources);
        }

        Path parent = configured.getParent();
        if (isExecutableNamed(configured, "ffprobe.exe", "ffprobe")) {
            Optional<Path> ffmpeg = findNamedExecutable(parent, "ffmpeg.exe", "ffmpeg");
            checkedSources.add("配置文件: " + configured + (ffmpeg.isPresent()
                    ? "（已从同目录解析 FFmpeg）" : "（同目录缺少 ffmpeg.exe/ffmpeg）"));
            return new FfmpegDiscoveryResult(ffmpeg, Optional.of(configured), checkedSources);
        }

        Optional<Path> ffprobe = findNamedExecutable(parent, "ffprobe.exe", "ffprobe");
        checkedSources.add("配置文件: " + configured + "（作为 FFmpeg 使用，"
                + companionStatus(ffprobe) + "）");
        return new FfmpegDiscoveryResult(Optional.of(configured), ffprobe, checkedSources);
    }

    private static Optional<Path> findNamedExecutable(Path directory, String... names) {
        if (directory == null || !Files.isDirectory(directory)) {
            return Optional.empty();
        }
        for (String name : names) {
            Path exact = directory.resolve(name);
            if (Files.isRegularFile(exact)) {
                return Optional.of(exact.toAbsolutePath().normalize());
            }
        }
        try (var entries = Files.list(directory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> isExecutableNamed(path, names))
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize());
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isExecutableNamed(Path path, String... names) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        for (String name : names) {
            if (fileName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String stripQuotes(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static String companionStatus(Optional<Path> ffprobe) {
        return ffprobe.isPresent() ? "已找到同目录 ffprobe" : "同目录未找到 ffprobe";
    }

    private static boolean isCandidate(Path path, boolean requireAbsolute) {
        return path != null && (!requireAbsolute || path.isAbsolute()) && Files.isRegularFile(path);
    }

    private static Path requireExecutable(Path path) {
        if (!isCandidate(path, false)) {
            throw new IllegalArgumentException("FFmpeg executable does not exist: " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    @FunctionalInterface
    interface CommandExecutor {
        CommandResult execute(List<String> command, Duration timeout) throws IOException, InterruptedException;
    }

    record CommandResult(int exitCode, String output) {
        CommandResult {
            output = output == null ? "" : output;
        }
    }

    private static final class SystemCommandExecutor implements CommandExecutor {
        @Override
        public CommandResult execute(List<String> command, Duration timeout) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            FutureTask<String> reader = new FutureTask<>(() ->
                    new String(process.getInputStream().readAllBytes(), Charset.defaultCharset()));
            Thread readerThread = Thread.ofVirtual().name("ffmpeg-probe-output").start(reader);
            boolean exited;
            try {
                exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                throw interrupted;
            }
            if (!exited) {
                process.destroy();
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
            try {
                readerThread.join(1_000);
                String output = reader.isDone() ? reader.get() : "";
                if (!exited) {
                    throw new IOException("FFmpeg command timed out after " + timeout + ": " + command);
                }
                return new CommandResult(process.exitValue(), output);
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new IOException("Failed to read FFmpeg command output", failure.getCause());
            }
        }
    }
}
