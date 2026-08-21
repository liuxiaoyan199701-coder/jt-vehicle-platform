package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.AlarmConfig;
import io.github.jtplatform.simulator.config.ConfigStore;
import io.github.jtplatform.simulator.config.DriverConfig;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.diagnostics.LogEntry;
import io.github.jtplatform.simulator.diagnostics.SimulatorLog;
import io.github.jtplatform.simulator.media.DirectShowDevices;
import io.github.jtplatform.simulator.media.FfmpegCapabilities;
import io.github.jtplatform.simulator.media.FfmpegDiscovery;
import io.github.jtplatform.simulator.media.FfmpegDiscoveryResult;
import io.github.jtplatform.simulator.media.MediaStats;
import io.github.jtplatform.simulator.signal.AlarmDefinition;
import io.github.jtplatform.simulator.signal.SignalClient;
import io.github.jtplatform.simulator.signal.SignalListener;
import io.github.jtplatform.simulator.signal.SignalState;
import io.github.jtplatform.simulator.stream.MediaController;
import io.github.jtplatform.simulator.stream.MediaListener;
import io.github.jtplatform.simulator.stream.MediaSnapshot;
import io.github.jtplatform.simulator.trip.RoutePlanner;
import io.github.jtplatform.simulator.trip.TripController;
import io.github.jtplatform.simulator.trip.TripViewState;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t808.T0001;

public final class SimulatorRuntime implements SimulatorOperations {
    private final ConfigStore configStore;
    private final FfmpegDiscovery ffmpegDiscovery;
    private final SimulatorLog log;
    private final ExecutorService background;
    private final AtomicReference<SimulatorConfig> currentConfig;
    private final AtomicReference<Throwable> previewStartFailure = new AtomicReference<>();
    private final Object configLock = new Object();
    private final MediaController mediaController;
    private final TripController tripController;
    private final AutoCloseable logSubscription;

    private volatile RuntimeListener listener = RuntimeListener.NOOP;
    private volatile SignalClient signalClient;
    private volatile boolean closed;
    private volatile long signalGeneration;

    public SimulatorRuntime() throws IOException {
        this(new ConfigStore(), new FfmpegDiscovery());
    }

    public SimulatorRuntime(ConfigStore configStore, FfmpegDiscovery ffmpegDiscovery) throws IOException {
        this.configStore = Objects.requireNonNull(configStore, "configStore");
        this.ffmpegDiscovery = Objects.requireNonNull(ffmpegDiscovery, "ffmpegDiscovery");
        this.log = new SimulatorLog(configStore.configFile().getParent());
        this.background = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("simulator-ui-worker-", 0).factory());
        this.currentConfig = new AtomicReference<>(SimulatorConfig.defaults());
        this.mediaController = new MediaController(currentConfig::get, new RuntimeMediaListener());
        // 与 MediaController 同构：构造一次，注入每一条连接。行程状态因此跨重连存活。
        this.tripController = new TripController(new RoutePlanner(), this::onTripState);
        this.logSubscription = log.addListener(entry -> listener.onLog(entry));
    }

    @Override
    public SimulatorConfig loadConfig() throws IOException {
        ensureOpen();
        synchronized (configLock) {
            SimulatorConfig loaded = configStore.load();
            currentConfig.set(loaded);
            return loaded;
        }
    }

    @Override
    public void saveConfig(SimulatorConfig config) throws IOException {
        ensureOpen();
        SimulatorConfig checked = Objects.requireNonNull(config, "config");
        synchronized (configLock) {
            configStore.save(checked);
            currentConfig.set(checked);
        }
        // 保存后立刻生效，免得「界面上写的」和「行程实际在用的」是两份配置。
        tripController.configure(checked.trip());
        log.info("config", "Configuration saved to " + configStore.configFile());
    }

    @Override
    public CompletionStage<FfmpegProbeResult> detectFfmpeg(String configuredPath) {
        ensureOpen();
        String requested = configuredPath == null ? "" : configuredPath.trim();
        return CompletableFuture.supplyAsync(() -> probeFfmpeg(requested), background);
    }

    @Override
    public synchronized void connect(SimulatorConfig config) {
        ensureOpen();
        SimulatorConfig checked = Objects.requireNonNull(config, "config");
        if (signalClient != null && signalClient.connectionRequested()) {
            return;
        }
        currentConfig.set(checked);
        tripController.configure(checked.trip());
        closeSignalClient();
        long generation = ++signalGeneration;
        SignalClient client = new SignalClient(
                checked,
                mediaController,
                new RuntimeSignalListener(generation),
                tripController);
        signalClient = client;
        for (AlarmDefinition alarm : AlarmDefinition.COMMON) {
            client.setAlarm(alarm.bit(), (checked.alarm().warnBits() & alarm.mask()) != 0);
        }
        if ((checked.alarm().warnBits() & AlarmDefinition.OVERSPEED_BIT) != 0) {
            client.setOverspeedKph((double) checked.alarm().overspeedKph());
        }
        log.info("signal", "Connecting to " + checked.signalHost() + ':' + checked.signalPort());
        client.connect();
    }

    @Override
    public synchronized void disconnect() {
        // 主动断开是明确的用户意图，行程随之停止；网络抖动导致的断线走另一条路径，行程要撑过去。
        tripController.onSessionClosed();
        SignalClient current = signalClient;
        if (current != null) {
            current.disconnect();
        }
    }

    @Override
    public CompletionStage<Void> startPreview(SimulatorConfig config) {
        ensureOpen();
        currentConfig.set(Objects.requireNonNull(config, "config"));
        previewStartFailure.set(null);
        return mediaController.startPreview().thenCompose(result -> {
            if (result == T0001.Success) {
                previewStartFailure.set(null);
                return CompletableFuture.completedFuture(null);
            }
            Throwable cause = previewStartFailure.getAndSet(null);
            String detail = cause == null
                    ? mediaController.snapshot().detail()
                    : safeMessage(cause);
            String message = "无法启动摄像头预览"
                    + (detail == null || detail.isBlank() ? "" : "：" + detail);
            return CompletableFuture.failedFuture(
                    cause == null ? new IllegalStateException(message)
                            : new IllegalStateException(message, cause));
        });
    }

    @Override
    public CompletionStage<Void> stopPreview() {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        return mediaController.stopPreview();
    }

    @Override
    public void startTrip() {
        ensureOpen();
        tripController.start();
    }

    @Override
    public void stopTrip() {
        if (!closed) {
            tripController.stop();
        }
    }

    @Override
    public TripViewState tripState() {
        return tripController.state();
    }

    private void onTripState(TripViewState state) {
        if (closed) {
            return;
        }
        listener.onTripState(state);
    }

    @Override
    public SimulatorConfig currentConfig() {
        return currentConfig.get();
    }

    @Override
    public SignalState signalState() {
        SignalClient current = signalClient;
        return current == null ? SignalState.DISCONNECTED : current.state();
    }

    @Override
    public MediaViewState mediaState() {
        return toViewState(mediaController.snapshot());
    }

    @Override
    public void setAlarm(AlarmDefinition alarm, boolean enabled) {
        SignalClient current = signalClient;
        if (current != null) {
            current.setAlarm(alarm.bit(), enabled);
        }
        updateAlarmConfig(alarm, enabled);
    }

    @Override
    public void clearAlarms() {
        SignalClient current = signalClient;
        if (current != null) {
            current.clearAlarms();
        }
        synchronized (configLock) {
            SimulatorConfig previous = currentConfig.get();
            SimulatorConfig updated = new SimulatorConfig(previous.signalHost(), previous.signalPort(), previous.version(),
                    previous.mobileNo(), previous.deviceId(), previous.channel(), previous.registration(),
                    previous.ffmpegPath(), previous.cameraName(), previous.microphoneName(), previous.mainProfile(),
                    previous.subProfile(), previous.previewWidth(), previous.previewHeight(), previous.previewFps(),
                    previous.maxPayloadBytes(), previous.trip(), previous.driver(), AlarmConfig.defaults(),
                    previous.simFormat());
            persistRuntimeConfig(updated);
        }
    }

    @Override
    public void setOverspeedKph(double speedKph) {
        SignalClient current = signalClient;
        if (current != null) {
            current.setOverspeedKph(speedKph);
        }
        synchronized (configLock) {
            SimulatorConfig previous = currentConfig.get();
            AlarmConfig alarm = new AlarmConfig(previous.alarm().warnBits(), (int) Math.round(speedKph));
            persistRuntimeConfig(new SimulatorConfig(previous.signalHost(), previous.signalPort(), previous.version(),
                    previous.mobileNo(), previous.deviceId(), previous.channel(), previous.registration(),
                    previous.ffmpegPath(), previous.cameraName(), previous.microphoneName(), previous.mainProfile(),
                    previous.subProfile(), previous.previewWidth(), previous.previewHeight(), previous.previewFps(),
                    previous.maxPayloadBytes(), previous.trip(), previous.driver(), alarm, previous.simFormat()));
        }
    }

    private void persistRuntimeConfig(SimulatorConfig updated) {
        currentConfig.set(updated);
        try {
            configStore.save(updated);
        } catch (IOException failure) {
            log.warn("config", "Runtime configuration save failed: " + safeMessage(failure));
        }
    }

    private void updateAlarmConfig(AlarmDefinition alarm, boolean enabled) {
        synchronized (configLock) {
            SimulatorConfig previous = currentConfig.get();
            int bits = enabled ? previous.alarm().warnBits() | alarm.mask()
                    : previous.alarm().warnBits() & ~alarm.mask();
            AlarmConfig next = new AlarmConfig(bits, previous.alarm().overspeedKph());
            currentConfig.set(new SimulatorConfig(previous.signalHost(), previous.signalPort(), previous.version(),
                    previous.mobileNo(), previous.deviceId(), previous.channel(), previous.registration(),
                    previous.ffmpegPath(), previous.cameraName(), previous.microphoneName(), previous.mainProfile(),
                    previous.subProfile(), previous.previewWidth(), previous.previewHeight(), previous.previewFps(),
                    previous.maxPayloadBytes(), previous.trip(), previous.driver(), next, previous.simFormat()));
        }
    }

    @Override
    public CompletionStage<Void> sendDriverCard(DriverConfig driver, SignalClient.DriverAction action) {
        SignalClient current = signalClient;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("808 signal is not online"));
        }
        synchronized (configLock) {
            SimulatorConfig previous = currentConfig.get();
            SimulatorConfig updated = new SimulatorConfig(previous.signalHost(), previous.signalPort(), previous.version(),
                    previous.mobileNo(), previous.deviceId(), previous.channel(), previous.registration(),
                    previous.ffmpegPath(), previous.cameraName(), previous.microphoneName(), previous.mainProfile(),
                    previous.subProfile(), previous.previewWidth(), previous.previewHeight(), previous.previewFps(),
                    previous.maxPayloadBytes(), previous.trip(), driver, previous.alarm(), previous.simFormat());
            currentConfig.set(updated);
            try {
                configStore.save(updated);
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        return current.sendDriverCard(driver, action).thenRun(() -> listener.onDriverAction(
                switch (action) {
                    case INSERT_CARD -> "0702 插卡上班已发送";
                    case REMOVE_CARD -> "0702 拔卡下班已发送";
                    case READ_FAILURE -> "0702 读卡失败已发送";
                }));
    }

    @Override
    public List<LogEntry> recentLogs() {
        return log.recentEntries();
    }

    @Override
    public void setListener(RuntimeListener listener) {
        this.listener = listener == null ? RuntimeListener.NOOP : listener;
    }

    private FfmpegProbeResult probeFfmpeg(String configuredPath) {
        try {
            Path configured = configuredPath.isBlank() ? null : Path.of(configuredPath);
            FfmpegDiscoveryResult discovery = ffmpegDiscovery.discover(configured);
            if (discovery.ffmpeg().isEmpty()) {
                String summary = "未找到 FFmpeg";
                String diagnostics = discovery.missingExecutableMessage();
                log.warn("ffmpeg", diagnostics);
                return FfmpegProbeResult.unavailable(summary, diagnostics);
            }

            Path resolved = discovery.ffmpeg().orElseThrow();
            FfmpegCapabilities capabilities = ffmpegDiscovery.inspect(resolved);
            DirectShowDevices devices = capabilities.directShow()
                    ? ffmpegDiscovery.enumerateDirectShow(resolved)
                    : new DirectShowDevices(List.of(), List.of(), capabilities.diagnostics());
            String summary = capabilitySummary(capabilities, devices);
            String persistenceWarning = capabilities.supported()
                    ? persistResolvedFfmpegPath(resolved) : "";
            String diagnostics = String.join(
                    System.lineSeparator(),
                    discovery.diagnostics(),
                    capabilities.diagnostics(),
                    devices.diagnostics(),
                    deviceGuidance(capabilities, devices),
                    persistenceWarning).strip();
            if (capabilities.supported()) {
                log.info("ffmpeg", summary + System.lineSeparator() + discovery.diagnostics()
                        + (persistenceWarning.isBlank()
                        ? "" : System.lineSeparator() + persistenceWarning));
            } else {
                log.warn("ffmpeg", summary + System.lineSeparator() + diagnostics);
            }
            return new FfmpegProbeResult(
                    Optional.of(resolved), capabilities, devices, summary, diagnostics);
        } catch (InvalidPathException invalidPath) {
            String message = "FFmpeg 路径格式无效: " + invalidPath.getInput();
            log.error("ffmpeg", message, invalidPath);
            return FfmpegProbeResult.unavailable(message, invalidPath.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            String message = "FFmpeg 检测已中断";
            log.error("ffmpeg", message, interrupted);
            return FfmpegProbeResult.unavailable(message, interrupted.getMessage());
        } catch (IOException | RuntimeException failure) {
            String message = "FFmpeg 检测失败: " + safeMessage(failure);
            log.error("ffmpeg", message, failure);
            return FfmpegProbeResult.unavailable(message, failure.getMessage());
        }
    }

    private static String capabilitySummary(
            FfmpegCapabilities capabilities,
            DirectShowDevices devices) {
        if (capabilities.supported()) {
            if (devices.videoDevices().isEmpty()) {
                return "FFmpeg 可用，但未发现摄像头";
            }
            if (devices.audioDevices().isEmpty()) {
                return "FFmpeg 可用，发现 " + devices.videoDevices().size()
                        + " 个摄像头，但未发现麦克风";
            }
            return "FFmpeg 可用，发现 " + devices.videoDevices().size()
                    + " 个摄像头、" + devices.audioDevices().size() + " 个麦克风";
        }
        List<String> missing = new java.util.ArrayList<>(3);
        if (!capabilities.directShow()) {
            missing.add("DirectShow");
        }
        if (!capabilities.libx264()) {
            missing.add("libx264");
        }
        if (!capabilities.pcmAlaw()) {
            missing.add("pcm_alaw");
        }
        return "FFmpeg 缺少必需能力: " + String.join("、", missing);
    }

    private String persistResolvedFfmpegPath(Path resolved) {
        String absolutePath = resolved.toAbsolutePath().normalize().toString();
        synchronized (configLock) {
            SimulatorConfig previous = currentConfig.get();
            if (absolutePath.equals(previous.ffmpegPath())) {
                return "";
            }
            SimulatorConfig updated = previous.withFfmpegPath(absolutePath);
            try {
                configStore.save(updated);
                currentConfig.set(updated);
                log.info("config", "Resolved FFmpeg path saved to " + configStore.configFile());
                return "";
            } catch (IOException failure) {
                String warning = "FFmpeg 已检测成功，但自动保存绝对路径失败：" + safeMessage(failure)
                        + "。请点击“保存配置”重试。";
                log.error("config", warning, failure);
                return warning;
            }
        }
    }

    private static String deviceGuidance(
            FfmpegCapabilities capabilities,
            DirectShowDevices devices) {
        if (!capabilities.supported()) {
            return "";
        }
        if (devices.videoDevices().isEmpty()) {
            return "未发现摄像头。请关闭可能占用摄像头的程序，在 Windows“设置 > 隐私和安全性 > 相机”中允许桌面应用访问相机，然后点击“刷新设备”。";
        }
        if (devices.audioDevices().isEmpty()) {
            return "未发现麦克风。请检查 Windows 麦克风隐私权限和设备占用，然后点击“刷新设备”。";
        }
        return "";
    }

    private boolean currentSignal(long generation) {
        return !closed && generation == signalGeneration;
    }

    private synchronized void closeSignalClient() {
        SignalClient previous = signalClient;
        signalClient = null;
        signalGeneration++;
        if (previous != null) {
            previous.close();
        }
    }

    private static MediaViewState toViewState(MediaSnapshot snapshot) {
        MediaStats.Snapshot stats = snapshot.stats();
        long dropped = saturatingAdd(stats.droppedPFrames(), stats.droppedOtherFrames());
        String stream = switch (snapshot.streamType()) {
            case 0 -> "主码流";
            case 1 -> "子码流";
            default -> "-";
        };
        return new MediaViewState(
                snapshot.state().name(),
                snapshot.target().map(Object::toString).orElse("-"),
                stream,
                snapshot.audioEnabled(),
                snapshot.videoEnabled(),
                stats.sentVideoFramesPerSecond(),
                stats.sentBitsPerSecond(),
                stats.queueDepth(),
                dropped,
                snapshot.detail());
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Simulator runtime is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        listener = RuntimeListener.NOOP;
        closeSignalClient();
        mediaController.close();
        background.shutdownNow();
        try {
            logSubscription.close();
        } catch (Exception ignored) {
            // Listener removal is best-effort during application shutdown.
        }
        try {
            log.close();
        } catch (IOException ignored) {
            // The process is already shutting down and no further writes are expected.
        }
    }

    private final class RuntimeSignalListener implements SignalListener {
        private final long generation;

        private RuntimeSignalListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onStateChanged(SignalState previous, SignalState current, String detail) {
            if (!currentSignal(generation)) {
                return;
            }
            log.info("signal", previous + " -> " + current
                    + (detail == null || detail.isBlank() ? "" : ": " + detail));
            // 会话建立后行程才有意义——它唯一的可观测副作用就是通过会话上报位置。
            if (current == SignalState.ONLINE) {
                tripController.onSessionEstablished();
            } else if (previous == SignalState.ONLINE) {
                tripController.onSessionLost();
            }
            listener.onSignalState(current, detail == null ? "" : detail);
        }

        @Override
        public void onMessageReceived(JTMessage message) {
            if (currentSignal(generation)) {
                log.info("signal", "Received message 0x%04X".formatted(message.getMessageId()));
            }
        }

        @Override
        public void onError(String context, Throwable error) {
            if (!currentSignal(generation)) {
                return;
            }
            log.error("signal", context + ": " + safeMessage(error), error);
            listener.onError(context, error);
        }

        @Override
        public void onDiagnostic(String message) {
            if (currentSignal(generation)) {
                log.info("signal", message);
            }
        }

        @Override
        public void onLocationReported(java.time.Instant timestamp) {
            if (currentSignal(generation)) {
                listener.onLocationReported(timestamp);
            }
        }
    }

    private final class RuntimeMediaListener implements MediaListener {
        @Override
        public void onStateChanged(MediaSnapshot snapshot) {
            MediaViewState state = toViewState(snapshot);
            if (!state.detail().isBlank()) {
                log.info("media", state.state() + ": " + state.detail());
            }
            listener.onMediaState(state);
        }

        @Override
        public void onPreviewFrame(byte[] jpeg) {
            if (!closed && jpeg != null && jpeg.length > 0) {
                listener.onPreviewFrame(java.util.Arrays.copyOf(jpeg, jpeg.length));
            }
        }

        @Override
        public void onDiagnostic(String message) {
            if (message != null && !message.isBlank()) {
                log.info("ffmpeg", message);
            }
        }

        @Override
        public void onError(String context, Throwable error) {
            if ("preview-start".equals(context)) {
                previewStartFailure.set(error);
            }
            log.error("media", context + ": " + safeMessage(error), error);
            listener.onError(context, error);
        }
    }
}
