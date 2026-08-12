package io.github.jtplatform.simulator.stream;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.config.VideoProfile;
import io.github.jtplatform.simulator.media.AnnexBAccessUnitParser;
import io.github.jtplatform.simulator.media.CaptureDevices;
import io.github.jtplatform.simulator.media.FfmpegCapabilities;
import io.github.jtplatform.simulator.media.FfmpegCaptureSession;
import io.github.jtplatform.simulator.media.FfmpegDiscovery;
import io.github.jtplatform.simulator.media.FfmpegDiscoveryResult;
import io.github.jtplatform.simulator.media.G711AFrameParser;
import io.github.jtplatform.simulator.media.MediaFrame;
import io.github.jtplatform.simulator.media.MediaTimeline;
import io.github.jtplatform.simulator.media.MjpegStreamParser;
import io.github.jtplatform.simulator.media.PreviewSettings;
import io.github.jtplatform.simulator.media.VideoAccessUnit;
import io.github.jtplatform.simulator.media.VideoSettings;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class FfmpegCaptureBackend implements CaptureBackend {
    private static final Duration OUTPUT_ACCEPT_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_H264_ACCESS_UNIT_BYTES = 8 * 1_024 * 1_024;
    private static final int MAX_MJPEG_FRAME_BYTES = 4 * 1_024 * 1_024;

    private final FfmpegDiscovery discovery;
    private final ConcurrentHashMap<Path, FfmpegCapabilities> capabilityCache = new ConcurrentHashMap<>();

    FfmpegCaptureBackend() {
        this(new FfmpegDiscovery());
    }

    FfmpegCaptureBackend(FfmpegDiscovery discovery) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    @Override
    public CaptureHandle startPreview(SimulatorConfig config, Callbacks callbacks) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(callbacks, "callbacks");
        Path executable = resolveExecutable(config);
        validateDevices(config, false);
        DiagnosticBuffer diagnostics = new DiagnosticBuffer(callbacks.diagnostics());
        FfmpegCaptureSession session = null;
        SessionHandle handle = null;
        try {
            session = FfmpegCaptureSession.startPreview(
                    executable,
                    config.cameraName(),
                    previewSettings(config),
                    diagnostics);
            handle = new SessionHandle(session, callbacks.failure());
            Socket preview = session.acceptMjpeg(OUTPUT_ACCEPT_TIMEOUT);
            MjpegStreamParser parser = new MjpegStreamParser(MAX_MJPEG_FRAME_BYTES);
            handle.startReader("preview", preview, bytes ->
                    parser.accept(bytes).forEach(callbacks.preview()));
            return handle;
        } catch (IOException | RuntimeException failure) {
            closeQuietly(handle == null ? session : handle);
            throw captureStartupFailure("摄像头预览", failure, diagnostics);
        }
    }

    @Override
    public CaptureHandle startCapture(
            SimulatorConfig config,
            VideoProfile profile,
            boolean includeAudio,
            Callbacks callbacks) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(callbacks, "callbacks");
        Path executable = resolveExecutable(config);
        validateDevices(config, includeAudio);
        CaptureDevices devices = includeAudio
                ? CaptureDevices.audioVideo(config.cameraName(), config.microphoneName())
                : CaptureDevices.videoOnly(config.cameraName());
        DiagnosticBuffer diagnostics = new DiagnosticBuffer(callbacks.diagnostics());
        FfmpegCaptureSession session = null;
        SessionHandle handle = null;
        try {
            session = FfmpegCaptureSession.startCapture(
                    executable,
                    devices,
                    new VideoSettings(profile.width(), profile.height(), profile.frameRate(),
                            profile.bitrateKbps(), profile.gopSeconds()),
                    previewSettings(config),
                    diagnostics);
            handle = new SessionHandle(session, callbacks.failure());
            Socket h264 = session.acceptH264(OUTPUT_ACCEPT_TIMEOUT);
            Optional<Socket> audio = session.acceptG711a(OUTPUT_ACCEPT_TIMEOUT);
            Socket preview = session.acceptMjpeg(OUTPUT_ACCEPT_TIMEOUT);
            MediaTimeline timeline = new MediaTimeline();
            AnnexBAccessUnitParser videoParser = new AnnexBAccessUnitParser(MAX_H264_ACCESS_UNIT_BYTES);
            G711AFrameParser audioParser = new G711AFrameParser(timeline);
            MjpegStreamParser previewParser = new MjpegStreamParser(MAX_MJPEG_FRAME_BYTES);
            long videoAnchor = timeline.nowMillis();
            long[] videoFrames = {0L};
            SessionHandle startedHandle = handle;

            startedHandle.startReader("h264", h264, bytes -> {
                for (VideoAccessUnit accessUnit : videoParser.accept(bytes)) {
                    long timestamp = timeline.timestampAfterSamples(
                            videoAnchor, videoFrames[0]++, profile.frameRate());
                    callbacks.media().accept(MediaFrame.video(accessUnit, timestamp));
                }
            }, () -> {
                for (VideoAccessUnit accessUnit : videoParser.flush()) {
                    long timestamp = timeline.timestampAfterSamples(
                            videoAnchor, videoFrames[0]++, profile.frameRate());
                    callbacks.media().accept(MediaFrame.video(accessUnit, timestamp));
                }
            });
            audio.ifPresent(socket -> startedHandle.startReader("g711a", socket, bytes ->
                    audioParser.accept(bytes).forEach(callbacks.media())));
            startedHandle.startReader("preview", preview, bytes ->
                    previewParser.accept(bytes).forEach(callbacks.preview()));
            return startedHandle;
        } catch (IOException | RuntimeException failure) {
            closeQuietly(handle == null ? session : handle);
            throw captureStartupFailure("音视频采集", failure, diagnostics);
        }
    }

    private Path resolveExecutable(SimulatorConfig config) throws IOException {
        Path configured;
        try {
            configured = config.ffmpegPath().isBlank() ? null : Path.of(config.ffmpegPath());
        } catch (InvalidPathException invalidPath) {
            throw new IOException("FFmpeg 配置路径格式无效：" + config.ffmpegPath()
                    + "。请点击“浏览...”重新选择 ffmpeg.exe。", invalidPath);
        }
        FfmpegDiscoveryResult discoveryResult = discovery.discover(configured);
        Path executable = discoveryResult.ffmpeg()
                .orElseThrow(() -> new IOException(discoveryResult.missingExecutableMessage()));
        FfmpegCapabilities capabilities;
        try {
            capabilities = capabilityCache.get(executable);
            if (capabilities == null) {
                capabilities = discovery.inspect(executable);
                capabilityCache.put(executable, capabilities);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("FFmpeg 能力检测已中断，请重试。", interrupted);
        }
        if (!capabilities.supported()) {
            List<String> missing = new ArrayList<>(3);
            if (!capabilities.directShow()) {
                missing.add("DirectShow");
            }
            if (!capabilities.libx264()) {
                missing.add("libx264");
            }
            if (!capabilities.pcmAlaw()) {
                missing.add("pcm_alaw");
            }
            throw new IOException("当前 FFmpeg 缺少必需能力：" + String.join("、", missing)
                    + "。请选择包含这些能力的 Windows FFmpeg 构建并重新检测。");
        }
        return executable;
    }

    private static void validateDevices(SimulatorConfig config, boolean includeAudio) {
        if (config.cameraName().isBlank()) {
            throw new IllegalArgumentException(
                    "未选择摄像头。请检查 Windows 相机权限并点击“刷新设备”，然后选择摄像头。");
        }
        if (includeAudio && config.microphoneName().isBlank()) {
            throw new IllegalArgumentException(
                    "音视频推流未选择麦克风。请检查 Windows 麦克风权限并点击“刷新设备”。");
        }
    }

    private static IOException captureStartupFailure(
            String operation,
            Throwable failure,
            DiagnosticBuffer diagnostics) {
        String reason = safeMessage(failure);
        String ffmpegOutput = diagnostics.summary();
        String message = "FFmpeg 无法启动" + operation + "：" + reason
                + (ffmpegOutput.isBlank() ? "" : System.lineSeparator() + "FFmpeg 诊断：" + ffmpegOutput)
                + System.lineSeparator()
                + "请关闭可能占用摄像头或麦克风的程序，检查 Windows 隐私权限，然后点击“刷新设备”重试。";
        return new IOException(message, failure);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Startup diagnostics from the original failure remain authoritative.
        }
    }

    private static PreviewSettings previewSettings(SimulatorConfig config) {
        return new PreviewSettings(
                config.previewWidth(), config.previewHeight(), config.previewFps(), 5);
    }

    @FunctionalInterface
    private interface BytesConsumer {
        void accept(byte[] bytes);
    }

    private static final class DiagnosticBuffer implements java.util.function.Consumer<String> {
        private static final int MAX_LINES = 8;
        private static final int MAX_LINE_LENGTH = 400;

        private final java.util.function.Consumer<String> delegate;
        private final ArrayDeque<String> lines = new ArrayDeque<>();

        private DiagnosticBuffer(java.util.function.Consumer<String> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void accept(String value) {
            String line = value == null ? "" : value.strip();
            if (!line.isEmpty()) {
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + "...";
                }
                synchronized (lines) {
                    lines.addLast(line);
                    while (lines.size() > MAX_LINES) {
                        lines.removeFirst();
                    }
                }
            }
            delegate.accept(value);
        }

        private String summary() {
            synchronized (lines) {
                return String.join(System.lineSeparator(), lines);
            }
        }
    }

    private static final class SessionHandle implements CaptureHandle {
        private final FfmpegCaptureSession session;
        private final java.util.function.Consumer<Throwable> failure;
        private final AtomicBoolean closing = new AtomicBoolean();
        private final List<Thread> readers = new ArrayList<>();

        private SessionHandle(
                FfmpegCaptureSession session,
                java.util.function.Consumer<Throwable> failure) {
            this.session = session;
            this.failure = failure;
            session.exit().whenComplete((exitCode, processFailure) -> {
                if (closing.get()) {
                    return;
                }
                Throwable cause = processFailure != null
                        ? processFailure
                        : new IOException("FFmpeg exited unexpectedly with code " + exitCode);
                failure.accept(cause);
            });
        }

        private void startReader(String name, Socket socket, BytesConsumer consumer) {
            startReader(name, socket, consumer, () -> { });
        }

        private synchronized void startReader(
                String name,
                Socket socket,
                BytesConsumer consumer,
                Runnable endOfInput) {
            Thread reader = Thread.ofVirtual().name("ffmpeg-" + name + "-reader").start(() -> {
                try (InputStream input = socket.getInputStream()) {
                    byte[] buffer = new byte[16 * 1_024];
                    int length;
                    while (!closing.get() && (length = input.read(buffer)) >= 0) {
                        if (length > 0) {
                            consumer.accept(java.util.Arrays.copyOf(buffer, length));
                        }
                    }
                    if (!closing.get()) {
                        endOfInput.run();
                        failure.accept(new IOException("FFmpeg " + name + " output closed"));
                    }
                } catch (IOException | RuntimeException readFailure) {
                    if (!closing.get()) {
                        failure.accept(readFailure);
                    }
                }
            });
            readers.add(reader);
        }

        @Override
        public void close() {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            session.close();
            List<Thread> snapshot;
            synchronized (this) {
                snapshot = List.copyOf(readers);
                readers.clear();
            }
            for (Thread reader : snapshot) {
                try {
                    reader.join(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
