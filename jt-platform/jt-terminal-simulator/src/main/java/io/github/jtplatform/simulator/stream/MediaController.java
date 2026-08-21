package io.github.jtplatform.simulator.stream;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.config.VideoProfile;
import io.github.jtplatform.simulator.media.MediaFrame;
import io.github.jtplatform.simulator.media.MediaFrameQueue;
import io.github.jtplatform.simulator.media.MediaFrameType;
import io.github.jtplatform.simulator.media.MediaStats;
import io.github.jtplatform.simulator.signal.SignalCommandHandler;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.yzh.protocol.t1078.T9101;
import org.yzh.protocol.t1078.T9102;
import org.yzh.protocol.t808.T0001;

public final class MediaController implements SignalCommandHandler, AutoCloseable {
    private static final Duration MEDIA_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final MediaStats EMPTY_STATS = new MediaStats();

    private final Supplier<SimulatorConfig> configSupplier;
    private final MediaListener listener;
    private final CaptureBackend captureBackend;
    private final WriterFactory writerFactory;
    private final ExecutorService lifecycle;
    private final AtomicReference<Thread> lifecycleThread = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile MediaState state = MediaState.IDLE;
    private volatile String detail = "";
    private volatile boolean previewRequested;
    private volatile CaptureBackend.CaptureHandle previewHandle;
    private volatile ActiveStream active;
    private long generation;

    public MediaController(Supplier<SimulatorConfig> configSupplier, MediaListener listener) {
        this(configSupplier, listener, new FfmpegCaptureBackend(),
                (target, config) -> Jt1078TcpWriter.connect(
                        target,
                        config.mobileNo(),
                        config.channel(),
                        config.maxPayloadBytes(),
                        MEDIA_CONNECT_TIMEOUT,
                        config.simFormat()));
    }

    MediaController(
            Supplier<SimulatorConfig> configSupplier,
            MediaListener listener,
            CaptureBackend captureBackend,
            WriterFactory writerFactory) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.listener = listener == null ? MediaListener.NOOP : listener;
        this.captureBackend = Objects.requireNonNull(captureBackend, "captureBackend");
        this.writerFactory = Objects.requireNonNull(writerFactory, "writerFactory");
        this.lifecycle = Executors.newSingleThreadExecutor(runnable ->
                Thread.ofVirtual().name("media-lifecycle-", 0).unstarted(() -> {
                    lifecycleThread.set(Thread.currentThread());
                    runnable.run();
                }));
    }

    public CompletionStage<Integer> startPreview() {
        previewRequested = true;
        return submitResult(() -> {
            if (active != null) {
                publish();
                return T0001.Success;
            }
            return startIdlePreview() ? T0001.Success : T0001.Failure;
        });
    }

    public CompletionStage<Void> stopPreview() {
        previewRequested = false;
        return submit(() -> {
            closePreview();
            if (active == null) {
                transition(MediaState.IDLE, "Preview stopped");
            } else {
                publish();
            }
        });
    }

    @Override
    public CompletionStage<Integer> open(T9101 command) {
        Objects.requireNonNull(command, "command");
        SimulatorConfig config;
        try {
            config = requireConfig();
        } catch (RuntimeException invalidConfig) {
            listener.onError("media-config", invalidConfig);
            return CompletableFuture.completedFuture(T0001.MessageError);
        }
        OpenRequest request;
        try {
            request = validateOpen(command, config);
        } catch (UnsupportedOperationException unsupported) {
            listener.onError("T9101", unsupported);
            return CompletableFuture.completedFuture(T0001.NotSupport);
        } catch (RuntimeException invalid) {
            listener.onError("T9101", invalid);
            return CompletableFuture.completedFuture(T0001.MessageError);
        }
        return submitResult(() -> replaceStream(request, config));
    }

    @Override
    public CompletionStage<Integer> control(T9102 command) {
        Objects.requireNonNull(command, "command");
        SimulatorConfig config;
        try {
            config = requireConfig();
        } catch (RuntimeException invalidConfig) {
            return CompletableFuture.completedFuture(T0001.MessageError);
        }
        if (command.getChannelNo() != config.channel()) {
            return CompletableFuture.completedFuture(T0001.MessageError);
        }
        return submitResult(() -> applyControl(command, config));
    }

    @Override
    public void onSignalDisconnected() {
        submit(() -> {
            if (active != null) {
                stopActive("Signal connection closed");
            }
            if (previewRequested) {
                startIdlePreview();
            }
        });
    }

    public MediaSnapshot snapshot() {
        ActiveStream current = active;
        return new MediaSnapshot(
                state,
                current == null ? Optional.empty() : Optional.of(current.target),
                current == null ? -1 : current.mediaType,
                current == null ? -1 : current.streamType,
                current != null && current.audioEnabled,
                current != null && current.videoEnabled,
                current != null || previewHandle != null,
                current == null ? EMPTY_STATS.snapshot() : current.stats.snapshot(),
                detail);
    }

    private int applyControl(T9102 command, SimulatorConfig config) {
        return switch (command.getCommand()) {
            case 0 -> closeMediaType(command.getCloseType());
            case 1 -> switchStream(command.getStreamType(), config);
            case 2 -> pause();
            case 3 -> resume();
            case 4 -> T0001.NotSupport;
            default -> T0001.NotSupport;
        };
    }

    private int closeMediaType(int closeType) {
        ActiveStream current = active;
        if (current == null) {
            return T0001.Success;
        }
        switch (closeType) {
            case 0 -> {
                stopActive("Platform closed audio and video");
                restorePreview();
            }
            case 1 -> current.audioEnabled = false;
            case 2 -> current.videoEnabled = false;
            default -> {
                return T0001.MessageError;
            }
        }
        if (current == active && !current.audioEnabled && !current.videoEnabled) {
            stopActive("No media type remains enabled");
            restorePreview();
        } else if (current == active) {
            current.queue.clear();
            current.awaitingKeyFrame = current.videoEnabled;
            transition(current.paused ? MediaState.PAUSED : MediaState.STREAMING,
                    closeType == 1 ? "Audio disabled" : "Video disabled");
        }
        return T0001.Success;
    }

    private int switchStream(int streamType, SimulatorConfig config) {
        if (streamType != 0 && streamType != 1) {
            return T0001.MessageError;
        }
        ActiveStream current = active;
        if (current == null) {
            return T0001.Failure;
        }
        OpenRequest replacement = new OpenRequest(
                current.target, current.mediaType, streamType,
                current.audioEnabled, current.videoEnabled);
        return replaceStream(replacement, config);
    }

    private int pause() {
        ActiveStream current = active;
        if (current == null) {
            return T0001.Failure;
        }
        current.paused = true;
        current.queue.clear();
        transition(MediaState.PAUSED, "Media transmission paused");
        return T0001.Success;
    }

    private int resume() {
        ActiveStream current = active;
        if (current == null) {
            return T0001.Failure;
        }
        current.paused = false;
        current.awaitingKeyFrame = current.videoEnabled;
        transition(MediaState.STREAMING, "Media transmission resumed");
        return T0001.Success;
    }

    private int replaceStream(OpenRequest request, SimulatorConfig config) {
        transition(MediaState.STARTING, "Connecting media target " + request.target);
        closePreview();
        if (active != null) {
            stopActive("Replacing active stream");
        }

        Jt1078TcpWriter writer = null;
        ActiveStream runtime = null;
        try {
            writer = writerFactory.connect(request.target, config);
            VideoProfile profile = request.streamType == 0
                    ? config.mainProfile() : config.subProfile();
            MediaStats stats = new MediaStats();
            int queueCapacity = Math.max(32,
                    profile.frameRate() * 2 + (request.audio ? 100 : 0));
            runtime = new ActiveStream(
                    ++generation,
                    request.target,
                    request.mediaType,
                    request.streamType,
                    request.audio,
                    request.video,
                    writer,
                    stats,
                    new MediaFrameQueue(queueCapacity, stats));
            active = runtime;
            ActiveStream callbackRuntime = runtime;
            runtime.capture = captureBackend.startCapture(
                    config,
                    profile,
                    request.audio,
                    callbacksFor(callbackRuntime));
            startWriter(callbackRuntime);
            transition(MediaState.STREAMING,
                    (request.streamType == 0 ? "Main" : "Sub") + " stream active at " + request.target);
            return T0001.Success;
        } catch (IOException | RuntimeException failure) {
            if (runtime != null) {
                runtime.close();
                if (active == runtime) {
                    active = null;
                }
            } else {
                closeQuietly(writer);
            }
            transition(MediaState.FAILED, "Unable to start media: " + safeMessage(failure));
            listener.onError("media-start", failure);
            restorePreview();
            return T0001.Failure;
        }
    }

    private CaptureBackend.Callbacks callbacksFor(ActiveStream runtime) {
        return new CaptureBackend.Callbacks(
                frame -> acceptFrame(runtime, frame),
                listener::onPreviewFrame,
                listener::onDiagnostic,
                failure -> handleRuntimeFailure(runtime, failure));
    }

    private void acceptFrame(ActiveStream runtime, MediaFrame frame) {
        if (runtime.closing.get() || runtime != active || runtime.paused) {
            return;
        }
        if (frame.type() == MediaFrameType.AUDIO && !runtime.audioEnabled) {
            return;
        }
        if (frame.type().video() && !runtime.videoEnabled) {
            return;
        }
        if (runtime.awaitingKeyFrame && frame.type() == MediaFrameType.VIDEO_P) {
            runtime.stats.recordDropped(frame);
            return;
        }
        if (frame.type() == MediaFrameType.VIDEO_I) {
            runtime.awaitingKeyFrame = false;
        }
        runtime.queue.offer(frame);
    }

    private void startWriter(ActiveStream runtime) {
        runtime.writerThread = Thread.ofVirtual().name("jt1078-writer-" + runtime.generation).start(() -> {
            try {
                while (!runtime.closing.get()) {
                    if (runtime.paused) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                        continue;
                    }
                    Optional<MediaFrame> ready = runtime.queue.pollReady(System.currentTimeMillis());
                    if (ready.isEmpty()) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                        continue;
                    }
                    MediaFrame frame = ready.orElseThrow();
                    if ((frame.type() == MediaFrameType.AUDIO && !runtime.audioEnabled)
                            || (frame.type().video() && !runtime.videoEnabled)) {
                        continue;
                    }
                    runtime.writer.write(frame);
                    runtime.stats.recordSent(frame);
                }
            } catch (IOException | RuntimeException failure) {
                if (!runtime.closing.get()) {
                    handleRuntimeFailure(runtime, failure);
                }
            }
        });
    }

    private void handleRuntimeFailure(ActiveStream runtime, Throwable failure) {
        submit(() -> {
            if (active != runtime || runtime.closing.get()) {
                return;
            }
            transition(MediaState.FAILED, "Media stopped: " + safeMessage(failure));
            listener.onError("media-runtime", failure);
            stopActive("Media runtime failure");
            restorePreview();
        });
    }

    private boolean startIdlePreview() {
        if (!previewRequested || active != null || previewHandle != null || closed.get()) {
            publish();
            return active != null || previewHandle != null;
        }
        transition(MediaState.STARTING, "Starting camera preview");
        long previewGeneration = ++generation;
        try {
            previewHandle = captureBackend.startPreview(requireConfig(), new CaptureBackend.Callbacks(
                    ignored -> { },
                    jpeg -> {
                        if (previewRequested && previewGeneration == generation) {
                            listener.onPreviewFrame(jpeg);
                        }
                    },
                    listener::onDiagnostic,
                    failure -> handlePreviewFailure(previewGeneration, failure)));
            transition(MediaState.PREVIEW, "Camera preview active");
            return true;
        } catch (IOException | RuntimeException failure) {
            previewHandle = null;
            transition(MediaState.FAILED, "Unable to start preview: " + safeMessage(failure));
            listener.onError("preview-start", failure);
            return false;
        }
    }

    private void handlePreviewFailure(long previewGeneration, Throwable failure) {
        submit(() -> {
            if (previewGeneration != generation || previewHandle == null) {
                return;
            }
            closePreview();
            transition(MediaState.FAILED, "Preview stopped: " + safeMessage(failure));
            listener.onError("preview-runtime", failure);
        });
    }

    private void restorePreview() {
        if (previewRequested && active == null) {
            startIdlePreview();
        } else if (active == null && state != MediaState.FAILED) {
            transition(MediaState.IDLE, "");
        }
    }

    private void stopActive(String reason) {
        ActiveStream current = active;
        if (current == null) {
            return;
        }
        active = null;
        current.close();
        if (!closed.get()) {
            transition(MediaState.IDLE, reason);
        }
    }

    private void closePreview() {
        CaptureBackend.CaptureHandle current = previewHandle;
        previewHandle = null;
        if (current != null) {
            current.close();
        }
    }

    private OpenRequest validateOpen(T9101 command, SimulatorConfig config) {
        if (command.getChannelNo() != config.channel()) {
            throw new IllegalArgumentException("T9101 channel does not match the configured channel");
        }
        if (command.getStreamType() != 0 && command.getStreamType() != 1) {
            throw new IllegalArgumentException("T9101 streamType must be 0 or 1");
        }
        boolean audio;
        boolean video;
        switch (command.getMediaType()) {
            case 0 -> {
                audio = true;
                video = true;
            }
            case 1 -> {
                audio = false;
                video = true;
            }
            case 3 -> {
                audio = true;
                video = false;
            }
            default -> throw new UnsupportedOperationException(
                    "Unsupported T9101 mediaType: " + command.getMediaType());
        }
        MediaTarget target = new MediaTarget(command.getIp(), command.getTcpPort());
        validateTarget(target, config);
        return new OpenRequest(
                target,
                command.getMediaType(), command.getStreamType(), audio, video);
    }

    private static void validateTarget(MediaTarget target, SimulatorConfig config) {
        InetAddress mediaAddress;
        try {
            mediaAddress = target.resolve();
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException(
                    "T9101 media host cannot be resolved or is not a unicast address: " + target.host(),
                    failure);
        }
        if (target.port() != config.signalPort()) {
            return;
        }
        if (target.host().equalsIgnoreCase(config.signalHost())) {
            throw new IllegalArgumentException("T9101 media target must not be the JT/T 808 signal endpoint");
        }
        try {
            for (InetAddress signalAddress : InetAddress.getAllByName(config.signalHost())) {
                if (mediaAddress.equals(signalAddress)) {
                    throw new IllegalArgumentException(
                            "T9101 media target must not be the JT/T 808 signal endpoint");
                }
            }
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException(
                    "Configured JT/T 808 signal host cannot be resolved: " + config.signalHost(), failure);
        }
    }

    private SimulatorConfig requireConfig() {
        return Objects.requireNonNull(configSupplier.get(), "simulator config");
    }

    private void transition(MediaState newState, String newDetail) {
        state = Objects.requireNonNull(newState, "newState");
        detail = newDetail == null ? "" : newDetail;
        publish();
    }

    private void publish() {
        listener.onStateChanged(snapshot());
    }

    private CompletionStage<Integer> submitResult(CheckedIntSupplier action) {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        if (closed.get()) {
            result.complete(T0001.Failure);
            return result;
        }
        try {
            lifecycle.execute(() -> {
                if (closed.get()) {
                    result.complete(T0001.Failure);
                    return;
                }
                try {
                    result.complete(action.getAsInt());
                } catch (Throwable failure) {
                    listener.onError("media-lifecycle", failure);
                    result.complete(T0001.Failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.complete(T0001.Failure);
        }
        return result;
    }

    private CompletionStage<Void> submit(Runnable action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (closed.get()) {
            result.complete(null);
            return result;
        }
        try {
            lifecycle.execute(() -> {
                if (closed.get()) {
                    result.complete(null);
                    return;
                }
                try {
                    action.run();
                    result.complete(null);
                } catch (Throwable failure) {
                    listener.onError("media-lifecycle", failure);
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.complete(null);
        }
        return result;
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
            // The original startup error remains the useful diagnostic.
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (Thread.currentThread() == lifecycleThread.get()) {
            closeResources();
            lifecycle.shutdown();
            return;
        }
        CompletableFuture<Void> finished = new CompletableFuture<>();
        try {
            lifecycle.execute(() -> {
                try {
                    closeResources();
                    finished.complete(null);
                } catch (Throwable failure) {
                    finished.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            finished.complete(null);
        }
        try {
            finished.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Shutdown continues by interrupting the lifecycle executor.
        } finally {
            lifecycle.shutdownNow();
        }
    }

    private void closeResources() {
        transition(MediaState.STOPPING, "Stopping media");
        closePreview();
        stopActive("Application stopped");
        state = MediaState.IDLE;
        detail = "";
        publish();
    }

    @FunctionalInterface
    interface WriterFactory {
        Jt1078TcpWriter connect(MediaTarget target, SimulatorConfig config) throws IOException;
    }

    @FunctionalInterface
    private interface CheckedIntSupplier {
        int getAsInt() throws Exception;
    }

    private record OpenRequest(
            MediaTarget target,
            int mediaType,
            int streamType,
            boolean audio,
            boolean video) {
    }

    private static final class ActiveStream implements AutoCloseable {
        private final long generation;
        private final MediaTarget target;
        private final int mediaType;
        private final int streamType;
        private final Jt1078TcpWriter writer;
        private final MediaStats stats;
        private final MediaFrameQueue queue;
        private final AtomicBoolean closing = new AtomicBoolean();

        private volatile boolean audioEnabled;
        private volatile boolean videoEnabled;
        private volatile boolean paused;
        private volatile boolean awaitingKeyFrame;
        private volatile CaptureBackend.CaptureHandle capture;
        private volatile Thread writerThread;

        private ActiveStream(
                long generation,
                MediaTarget target,
                int mediaType,
                int streamType,
                boolean audioEnabled,
                boolean videoEnabled,
                Jt1078TcpWriter writer,
                MediaStats stats,
                MediaFrameQueue queue) {
            this.generation = generation;
            this.target = target;
            this.mediaType = mediaType;
            this.streamType = streamType;
            this.audioEnabled = audioEnabled;
            this.videoEnabled = videoEnabled;
            this.awaitingKeyFrame = videoEnabled;
            this.writer = writer;
            this.stats = stats;
            this.queue = queue;
        }

        @Override
        public void close() {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(writer);
            closeQuietly(capture);
            queue.clear();
            Thread currentWriter = writerThread;
            if (currentWriter != null && currentWriter != Thread.currentThread()) {
                try {
                    currentWriter.join(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
