package io.github.jtplatform.simulator.media;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class FfmpegCaptureSession implements AutoCloseable {
    private final LoopbackOutput h264;
    private final LoopbackOutput g711a;
    private final LoopbackOutput mjpeg;
    private final FfmpegProcess process;
    private final List<Socket> accepted = new ArrayList<>();

    private FfmpegCaptureSession(
            LoopbackOutput h264,
            LoopbackOutput g711a,
            LoopbackOutput mjpeg,
            FfmpegProcess process) {
        this.h264 = h264;
        this.g711a = g711a;
        this.mjpeg = mjpeg;
        this.process = process;
    }

    public static FfmpegCaptureSession startPreview(
            Path executable,
            String camera,
            PreviewSettings preview,
            Consumer<String> diagnostics) throws IOException {
        LoopbackOutput mjpeg = LoopbackOutput.open();
        try {
            List<String> command = new FfmpegCommandBuilder().preview(executable, camera, preview, mjpeg.target());
            FfmpegProcess process = FfmpegProcess.start(command, diagnostics);
            return new FfmpegCaptureSession(null, null, mjpeg, process);
        } catch (IOException | RuntimeException failure) {
            mjpeg.close();
            throw failure;
        }
    }

    public static FfmpegCaptureSession startCapture(
            Path executable,
            CaptureDevices devices,
            VideoSettings video,
            PreviewSettings preview,
            Consumer<String> diagnostics) throws IOException {
        Objects.requireNonNull(devices, "devices");
        LoopbackOutput h264 = LoopbackOutput.open();
        LoopbackOutput audio = null;
        LoopbackOutput mjpeg = null;
        try {
            if (devices.microphone().isPresent()) {
                audio = LoopbackOutput.open();
            }
            mjpeg = LoopbackOutput.open();
            FfmpegOutputTargets targets = new FfmpegOutputTargets(
                    h264.target(), Optional.ofNullable(audio).map(LoopbackOutput::target), mjpeg.target());
            List<String> command = new FfmpegCommandBuilder().capture(
                    executable, devices, video, preview, targets);
            FfmpegProcess process = FfmpegProcess.start(command, diagnostics);
            return new FfmpegCaptureSession(h264, audio, mjpeg, process);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(mjpeg);
            closeQuietly(audio);
            closeQuietly(h264);
            throw failure;
        }
    }

    public Socket acceptH264(Duration timeout) throws IOException {
        if (h264 == null) {
            throw new IllegalStateException("This is a preview-only FFmpeg session");
        }
        return remember(h264.accept(timeout));
    }

    public Optional<Socket> acceptG711a(Duration timeout) throws IOException {
        return g711a == null ? Optional.empty() : Optional.of(remember(g711a.accept(timeout)));
    }

    public Socket acceptMjpeg(Duration timeout) throws IOException {
        return remember(mjpeg.accept(timeout));
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public CompletableFuture<Integer> exit() {
        return process.exit();
    }

    @Override
    public synchronized void close() {
        process.close();
        for (Socket socket : accepted) {
            closeQuietly(socket);
        }
        accepted.clear();
        closeQuietly(mjpeg);
        closeQuietly(g711a);
        closeQuietly(h264);
    }

    private synchronized Socket remember(Socket socket) {
        accepted.add(socket);
        return socket;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Session teardown is best-effort and idempotent.
        }
    }
}
