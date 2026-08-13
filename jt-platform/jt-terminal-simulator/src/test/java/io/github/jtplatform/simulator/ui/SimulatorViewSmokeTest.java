package io.github.jtplatform.simulator.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.diagnostics.LogEntry;
import io.github.jtplatform.simulator.diagnostics.LogLevel;
import io.github.jtplatform.simulator.media.DirectShowDevice;
import io.github.jtplatform.simulator.media.DirectShowDevices;
import io.github.jtplatform.simulator.media.FfmpegCapabilities;
import io.github.jtplatform.simulator.signal.SignalState;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class SimulatorViewSmokeTest {
    private static final byte[] PIXEL_IMAGE = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @BeforeAll
    static void startJavaFx() throws Exception {
        Platform.setImplicitExit(false);
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopJavaFx() {
        Platform.exit();
    }

    @Test
    @Timeout(30)
    void startsWithoutDevicesAndBindsTheOperationalWorkflow() throws Exception {
        runOnFxAndWait(() -> {
            FakeOperations operations = new FakeOperations();
            SimulatorView view = new SimulatorView(operations, SimulatorConfig.defaults());
            Stage stage = new Stage();
            Scene scene = new Scene(view, 960, 640);
            scene.getStylesheets().add(SimulatorApplication.stylesheetUrl());
            stage.setScene(scene);
            stage.sizeToScene();
            stage.show();
            view.activate();
            view.applyCss();
            view.layout();

            assertNotNull(view.lookup("#preview-surface"));
            assertNotNull(view.lookup("#runtime-log"));
            assertEquals(1, operations.detectCalls);
            assertEquals("Camera One", selected(view, "#camera"));
            assertEquals("Microphone One", selected(view, "#microphone"));

            TextField mobile = field(view, "#mobile-no");
            Button connect = button(view, "#connect-toggle");
            mobile.setText("12a");
            connect.fire();
            assertEquals(0, operations.connectCalls);
            assertTrue(mobile.getPseudoClassStates().stream()
                    .anyMatch(state -> state.getPseudoClassName().equals("invalid")));

            mobile.setText(SimulatorConfig.defaults().mobileNo());
            connect.fire();
            assertEquals(1, operations.connectCalls);
            assertTrue(field(view, "#signal-host").isDisabled());
            assertEquals("断开", connect.getText());
            connect.fire();
            assertFalse(field(view, "#signal-host").isDisabled());

            Button preview = button(view, "#preview-toggle");
            assertFalse(preview.isDisabled());
            preview.fire();
            assertEquals(1, operations.previewStarts);
            assertEquals("停止预览", preview.getText());
            preview.fire();
            assertEquals(1, operations.previewStops);

            operations.emitMedia(new MediaViewState(
                    "STREAMING", "203.0.113.8:7801", "主码流", true, true,
                    24.5, 1_500_000, 7, 3, "T9101 推流中"));
            operations.emitPreview(PIXEL_IMAGE);
            operations.emitLog(new LogEntry(
                    Instant.parse("2026-08-11T12:00:00Z"),
                    LogLevel.INFO,
                    "media",
                    "T9101 target=203.0.113.8:7801"));
            assertEquals("203.0.113.8:7801", label(view, "#target-node").getText());
            assertEquals("24.5 fps", label(view, "#media-fps").getText());
            assertNotNull(((javafx.scene.image.ImageView) view.lookup("#preview-image")).getImage());

            assertContained(view, view.lookup("#connect-toggle"));
            assertContained(view, view.lookup("#ffmpeg-state"));
            writeSnapshot(view, Path.of("target", "ui-smoke", "simulator-960x640.png"));

            double decorationWidth = stage.getWidth() - scene.getWidth();
            double decorationHeight = stage.getHeight() - scene.getHeight();
            stage.setWidth(1_440 + decorationWidth);
            stage.setHeight(900 + decorationHeight);
            view.resize(1_440, 900);
            view.applyCss();
            view.layout();
            assertContained(view, view.lookup("#activity-state"));
            writeSnapshot(view, Path.of("target", "ui-smoke", "simulator-1440x900.png"));

            view.close();
            stage.close();
            assertTrue(operations.closed);
        });
    }

    @Test
    @Timeout(20)
    void givesActionableWindowsGuidanceWhenNoCameraIsEnumerated() throws Exception {
        runOnFxAndWait(() -> {
            FakeOperations operations = new FakeOperations(noCameraProbe());
            SimulatorView view = new SimulatorView(operations, SimulatorConfig.defaults());
            Stage stage = new Stage();
            stage.setScene(new Scene(view, 960, 640));
            stage.show();

            view.activate();
            view.applyCss();
            view.layout();

            assertEquals("FFmpeg 可用，但未发现摄像头",
                    label(view, "#ffmpeg-state").getText());
            assertTrue(label(view, "#activity-state").getText().contains("Windows 相机隐私权限"));

            view.close();
            stage.close();
        });
    }

    private static FfmpegProbeResult noCameraProbe() {
        DirectShowDevices devices = new DirectShowDevices(
                List.of(),
                List.of(new DirectShowDevice(
                        DirectShowDevice.Type.AUDIO, "Microphone One", "@microphone")),
                "DirectShow devices");
        return new FfmpegProbeResult(
                Optional.of(Path.of("C:\\tools\\ffmpeg.exe")),
                new FfmpegCapabilities(
                        "ffmpeg version test", true, true, true, "capabilities"),
                devices,
                "FFmpeg 可用，但未发现摄像头",
                "请检查 Windows 相机隐私权限并刷新设备");
    }

    private static TextField field(SimulatorView view, String selector) {
        return (TextField) view.lookup(selector);
    }

    private static Button button(SimulatorView view, String selector) {
        return (Button) view.lookup(selector);
    }

    private static Label label(SimulatorView view, String selector) {
        return (Label) view.lookup(selector);
    }

    @SuppressWarnings("unchecked")
    private static String selected(SimulatorView view, String selector) {
        ComboBox<String> combo = (ComboBox<String>) view.lookup(selector);
        return combo.getEditor().getText();
    }

    private static void assertContained(SimulatorView view, Node node) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        assertTrue(bounds.getMinX() >= 0);
        assertTrue(bounds.getMinY() >= 0);
        assertTrue(bounds.getMaxX() <= view.getWidth() + 0.5);
        assertTrue(bounds.getMaxY() <= view.getHeight() + 0.5);
    }

    private static void writeSnapshot(SimulatorView view, Path path) throws IOException {
        WritableImage image = view.snapshot(null, null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] pixels = new int[Math.multiplyExact(width, height)];
        image.getPixelReader().getPixels(
                0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0, 0, width, height, pixels, 0, width);
        Files.createDirectories(path.getParent());
        assertTrue(ImageIO.write(output, "png", path.toFile()));
    }

    private static void runOnFxAndWait(ThrowingRunnable action) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                complete.countDown();
            }
        });
        assertTrue(complete.await(25, TimeUnit.SECONDS));
        if (failure.get() != null) {
            if (failure.get() instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError(failure.get());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FakeOperations implements SimulatorOperations {
        private RuntimeListener listener = RuntimeListener.NOOP;
        private SimulatorConfig config = SimulatorConfig.defaults();
        private SignalState signalState = SignalState.DISCONNECTED;
        private MediaViewState mediaState = MediaViewState.idle();
        private int detectCalls;
        private int connectCalls;
        private int previewStarts;
        private int previewStops;
        private boolean closed;
        private final FfmpegProbeResult probeResult;

        private FakeOperations() {
            this(availableProbe());
        }

        private FakeOperations(FfmpegProbeResult probeResult) {
            this.probeResult = probeResult;
        }

        @Override
        public SimulatorConfig loadConfig() {
            return config;
        }

        @Override
        public void saveConfig(SimulatorConfig config) {
            this.config = config;
        }

        @Override
        public CompletionStage<FfmpegProbeResult> detectFfmpeg(String configuredPath) {
            detectCalls++;
            return CompletableFuture.completedFuture(probeResult);
        }

        private static FfmpegProbeResult availableProbe() {
            DirectShowDevices devices = new DirectShowDevices(
                    List.of(new DirectShowDevice(
                            DirectShowDevice.Type.VIDEO, "Camera One", "@camera")),
                    List.of(new DirectShowDevice(
                            DirectShowDevice.Type.AUDIO, "Microphone One", "@microphone")),
                    "DirectShow devices");
            return new FfmpegProbeResult(
                    Optional.of(Path.of("C:\\tools\\ffmpeg.exe")),
                    new FfmpegCapabilities(
                            "ffmpeg version test", true, true, true, "capabilities"),
                    devices,
                    "FFmpeg 可用，发现 1 个摄像头、1 个麦克风",
                    "capabilities");
        }

        @Override
        public void connect(SimulatorConfig config) {
            connectCalls++;
            this.config = config;
            signalState = SignalState.ONLINE;
            listener.onSignalState(signalState, "Authenticated");
        }

        @Override
        public void disconnect() {
            signalState = SignalState.DISCONNECTED;
            listener.onSignalState(signalState, "Disconnected");
        }

        @Override
        public CompletionStage<Void> startPreview(SimulatorConfig config) {
            previewStarts++;
            this.config = config;
            emitMedia(new MediaViewState(
                    "PREVIEW", "-", "-", false, false, 0, 0, 0, 0,
                    "Camera preview active"));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> stopPreview() {
            previewStops++;
            emitMedia(MediaViewState.idle());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public SignalState signalState() {
            return signalState;
        }

        @Override
        public MediaViewState mediaState() {
            return mediaState;
        }

        @Override
        public List<LogEntry> recentLogs() {
            return List.of();
        }

        @Override
        public void setListener(RuntimeListener listener) {
            this.listener = listener == null ? RuntimeListener.NOOP : listener;
        }

        @Override
        public void close() {
            closed = true;
        }

        private void emitMedia(MediaViewState state) {
            mediaState = state;
            listener.onMediaState(state);
        }

        private void emitPreview(byte[] image) {
            listener.onPreviewFrame(image);
        }

        private void emitLog(LogEntry entry) {
            listener.onLog(entry);
        }
    }
}
