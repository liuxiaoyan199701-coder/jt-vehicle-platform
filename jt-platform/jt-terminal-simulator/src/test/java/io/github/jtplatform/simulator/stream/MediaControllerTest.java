package io.github.jtplatform.simulator.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.config.VideoProfile;
import io.github.jtplatform.simulator.media.MediaFrame;
import io.github.jtplatform.simulator.media.MediaFrameType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.t1078.T9101;
import org.yzh.protocol.t1078.T9102;
import org.yzh.protocol.t808.T0001;

class MediaControllerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void validatesTargetsSupportsMediaModesAndReplacesOnlyOneActiveCapture() throws Exception {
        SimulatorConfig config = config();
        RecordingCaptureBackend captures = new RecordingCaptureBackend();
        RecordingWriterFactory writers = new RecordingWriterFactory();
        RecordingListener listener = new RecordingListener();

        try (MediaController controller = new MediaController(() -> config, listener, captures, writers)) {
            assertEquals(T0001.Success, await(controller.startPreview()));
            assertEquals(MediaState.PREVIEW, controller.snapshot().state());

            assertEquals(T0001.Success, await(controller.open(open(0, 0, "127.0.0.1", 17_801))));
            CaptureRun audioVideo = captures.latestCapture();
            WriterRun mainWriter = writers.latest();
            assertSame(config.mainProfile(), audioVideo.profile());
            assertTrue(audioVideo.includeAudio());
            assertEquals(0, controller.snapshot().mediaType());
            assertTrue(controller.snapshot().audioEnabled());
            assertTrue(controller.snapshot().videoEnabled());

            assertEquals(T0001.Success, await(controller.open(open(1, 1, "localhost", 17_802))));
            CaptureRun videoOnly = captures.latestCapture();
            assertTrue(audioVideo.handle().closed());
            assertTrue(mainWriter.output().closed());
            assertSame(config.subProfile(), videoOnly.profile());
            assertFalse(videoOnly.includeAudio());
            assertFalse(controller.snapshot().audioEnabled());
            assertTrue(controller.snapshot().videoEnabled());
            assertEquals(1, controller.snapshot().streamType());

            assertEquals(T0001.Success, await(controller.open(open(3, 0, "127.0.0.1", 17_803))));
            CaptureRun audioOnly = captures.latestCapture();
            assertTrue(videoOnly.handle().closed());
            assertTrue(audioOnly.includeAudio());
            assertTrue(controller.snapshot().audioEnabled());
            assertFalse(controller.snapshot().videoEnabled());
            assertEquals(1, captures.maximumActiveHandles());

            int writerCount = writers.runs().size();
            assertEquals(T0001.NotSupport, await(controller.open(open(2, 0, "127.0.0.1", 17_804))));
            assertEquals(T0001.MessageError, await(controller.open(
                    open(0, 0, "127.0.0.1", 17_804).setChannelNo(2))));
            assertEquals(T0001.MessageError, await(controller.open(open(0, 2, "127.0.0.1", 17_804))));
            assertEquals(T0001.MessageError, await(controller.open(open(0, 0, "0.0.0.0", 17_804))));
            assertEquals(T0001.MessageError, await(controller.open(
                    open(0, 0, "127.0.0.1", config.signalPort()))));
            assertEquals(writerCount, writers.runs().size());
            assertEquals(3, controller.snapshot().mediaType());
        }
    }

    @Test
    void writesConfiguredMobileNoIntoTheRtpSimField() throws Exception {
        SimulatorConfig config = config();
        RecordingCaptureBackend captures = new RecordingCaptureBackend();
        RecordingWriterFactory writers = new RecordingWriterFactory();

        try (MediaController controller = new MediaController(
                () -> config, MediaListener.NOOP, captures, writers)) {
            assertEquals(T0001.Success, await(controller.open(open(3, 0, "127.0.0.1", 17_805))));
            captures.latestCapture().emit(audio(System.currentTimeMillis() - 1_000, 1));
            awaitCondition(() -> writers.latest().output().size() > 0);

            byte[] packet = writers.latest().output().bytes();
            assertArrayEquals(
                    HexFormat.of().parseHex(config.mobileNo()),
                    Arrays.copyOfRange(packet, 8, 14));
        }
    }

    @Test
    void pausesResumesClosesMediaTypesSwitchesProfileAndRestoresPreview() throws Exception {
        SimulatorConfig config = config();
        RecordingCaptureBackend captures = new RecordingCaptureBackend();
        RecordingWriterFactory writers = new RecordingWriterFactory();

        try (MediaController controller = new MediaController(
                () -> config, MediaListener.NOOP, captures, writers)) {
            assertEquals(T0001.Success, await(controller.startPreview()));
            assertEquals(T0001.Success, await(controller.open(open(0, 0, "127.0.0.1", 17_811))));
            CaptureRun mainCapture = captures.latestCapture();
            WriterRun mainWriter = writers.latest();
            long base = System.currentTimeMillis() - 1_000;
            mainCapture.emit(video(MediaFrameType.VIDEO_I, base, 1));
            mainCapture.emit(audio(base + 10, 2));
            mainCapture.emit(video(MediaFrameType.VIDEO_P, base + 20, 3));
            awaitCondition(() -> controller.snapshot().stats().sentVideoFrames() == 2
                    && controller.snapshot().stats().sentAudioFrames() == 1);

            assertEquals(T0001.Success, await(controller.control(control(2, 0, 0))));
            assertEquals(MediaState.PAUSED, controller.snapshot().state());
            int pausedBytes = mainWriter.output().size();
            mainCapture.emit(video(MediaFrameType.VIDEO_I, base + 30, 4));
            mainCapture.emit(audio(base + 40, 5));
            Thread.sleep(100);
            assertEquals(pausedBytes, mainWriter.output().size());

            assertEquals(T0001.Success, await(controller.control(control(3, 0, 0))));
            mainCapture.emit(video(MediaFrameType.VIDEO_P, base + 50, 6));
            mainCapture.emit(audio(base + 60, 7));
            mainCapture.emit(video(MediaFrameType.VIDEO_I, base + 70, 8));
            awaitCondition(() -> controller.snapshot().stats().sentVideoFrames() == 3
                    && controller.snapshot().stats().sentAudioFrames() == 2);
            assertEquals(1, controller.snapshot().stats().droppedPFrames());

            assertEquals(T0001.Success, await(controller.control(control(0, 1, 0))));
            assertFalse(controller.snapshot().audioEnabled());
            assertTrue(controller.snapshot().videoEnabled());
            mainCapture.emit(audio(base + 80, 9));
            mainCapture.emit(video(MediaFrameType.VIDEO_P, base + 90, 10));
            mainCapture.emit(video(MediaFrameType.VIDEO_I, base + 100, 11));
            awaitCondition(() -> controller.snapshot().stats().sentVideoFrames() == 4);
            assertEquals(2, controller.snapshot().stats().sentAudioFrames());

            MediaTarget target = controller.snapshot().target().orElseThrow();
            assertEquals(T0001.Success, await(controller.control(control(1, 0, 1))));
            CaptureRun subCapture = captures.latestCapture();
            assertTrue(mainCapture.handle().closed());
            assertTrue(mainWriter.output().closed());
            assertSame(config.subProfile(), subCapture.profile());
            assertFalse(subCapture.includeAudio());
            assertEquals(target, controller.snapshot().target().orElseThrow());
            assertEquals(1, controller.snapshot().streamType());
            assertFalse(controller.snapshot().audioEnabled());
            assertTrue(controller.snapshot().videoEnabled());

            assertEquals(T0001.Success, await(controller.control(control(0, 2, 0))));
            awaitCondition(() -> controller.snapshot().target().isEmpty()
                    && controller.snapshot().state() == MediaState.PREVIEW);
            assertTrue(subCapture.handle().closed());
            assertTrue(writers.latest().output().closed());
            assertTrue(captures.previewRuns().size() >= 2);
            assertEquals(1, captures.maximumActiveHandles());

            assertEquals(T0001.Success, await(controller.open(open(0, 0, "127.0.0.1", 17_811))));
            WriterRun reopened = writers.latest();
            assertEquals(T0001.Success, await(controller.control(control(0, 0, 0))));
            awaitCondition(() -> controller.snapshot().target().isEmpty());
            assertTrue(reopened.output().closed());
        }
    }

    @Test
    void isolatesCaptureWriterAndSignalFailuresAndAcceptsANewSchedule() throws Exception {
        SimulatorConfig config = config();
        RecordingCaptureBackend captures = new RecordingCaptureBackend();
        RecordingWriterFactory writers = new RecordingWriterFactory();
        RecordingListener listener = new RecordingListener();

        try (MediaController controller = new MediaController(() -> config, listener, captures, writers)) {
            assertEquals(T0001.Success, await(controller.startPreview()));
            assertEquals(T0001.Success, await(controller.open(open(0, 0, "127.0.0.1", 17_821))));
            CaptureRun failedCapture = captures.latestCapture();
            WriterRun captureWriter = writers.latest();

            failedCapture.fail(new IOException("synthetic FFmpeg failure"));
            awaitCondition(() -> controller.snapshot().target().isEmpty()
                    && controller.snapshot().state() == MediaState.PREVIEW);
            assertTrue(failedCapture.handle().closed());
            assertTrue(captureWriter.output().closed());

            writers.failNextWrite();
            assertEquals(T0001.Success, await(controller.open(open(1, 0, "127.0.0.1", 17_822))));
            CaptureRun writerFailureCapture = captures.latestCapture();
            WriterRun failedWriter = writers.latest();
            writerFailureCapture.emit(video(
                    MediaFrameType.VIDEO_I, System.currentTimeMillis() - 1_000, 20));
            awaitCondition(() -> controller.snapshot().target().isEmpty()
                    && controller.snapshot().state() == MediaState.PREVIEW);
            assertTrue(failedWriter.output().closed());
            assertTrue(listener.errorContexts().stream().filter("media-runtime"::equals).count() >= 2);

            assertEquals(T0001.Success, await(controller.open(open(3, 1, "127.0.0.1", 17_823))));
            WriterRun disconnectedWriter = writers.latest();
            controller.onSignalDisconnected();
            awaitCondition(() -> controller.snapshot().target().isEmpty()
                    && controller.snapshot().state() == MediaState.PREVIEW);
            assertTrue(disconnectedWriter.output().closed());

            assertEquals(T0001.Success, await(controller.open(open(1, 0, "127.0.0.1", 17_824))));
            assertEquals(MediaState.STREAMING, controller.snapshot().state());
        }
    }

    @Test
    void closeIsIdempotentAndRejectsNewLifecycleWork() throws Exception {
        SimulatorConfig config = config();
        RecordingCaptureBackend captures = new RecordingCaptureBackend();
        RecordingWriterFactory writers = new RecordingWriterFactory();
        MediaController controller = new MediaController(
                () -> config, MediaListener.NOOP, captures, writers);
        assertEquals(T0001.Success, await(controller.open(open(0, 0, "127.0.0.1", 17_831))));
        WriterRun activeWriter = writers.latest();

        controller.close();
        controller.close();

        assertTrue(activeWriter.output().closed());
        assertEquals(MediaState.IDLE, controller.snapshot().state());
        assertEquals(T0001.Failure, await(controller.open(open(0, 0, "127.0.0.1", 17_832))));
        assertEquals(T0001.Failure, await(controller.startPreview()));
        controller.stopPreview().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    void releasesTheMediaSocketBeforeWaitingForCaptureTeardown() throws Exception {
        SimulatorConfig config = config();
        BlockingCloseCaptureBackend captures = new BlockingCloseCaptureBackend();
        RecordingWriterFactory writers = new RecordingWriterFactory();

        try (MediaController controller = new MediaController(
                () -> config, MediaListener.NOOP, captures, writers)) {
            assertEquals(T0001.Success, await(controller.open(open(0, 0, "127.0.0.1", 17_841))));
            CompletionStage<Integer> closing = controller.control(control(0, 0, 0));
            try {
                assertTrue(captures.closeEntered.await(1, TimeUnit.SECONDS));
                assertTrue(writers.latest().output().closed());
            } finally {
                captures.allowClose.countDown();
            }
            assertEquals(T0001.Success, await(closing));
        }
    }

    private static int await(CompletionStage<Integer> operation) throws Exception {
        return operation.toCompletableFuture().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition was not met within " + TIMEOUT);
            }
            Thread.sleep(10);
        }
    }

    private static T9101 open(int mediaType, int streamType, String host, int port) {
        return new T9101()
                .setIp(host)
                .setTcpPort(port)
                .setUdpPort(0)
                .setChannelNo(1)
                .setMediaType(mediaType)
                .setStreamType(streamType);
    }

    private static T9102 control(int command, int closeType, int streamType) {
        return new T9102()
                .setChannelNo(1)
                .setCommand(command)
                .setCloseType(closeType)
                .setStreamType(streamType);
    }

    private static MediaFrame video(MediaFrameType type, long timestamp, int value) {
        return new MediaFrame(type, timestamp, new byte[] {
                0, 0, 0, 1, 0x09, 0x10,
                0, 0, 0, 1,
                type == MediaFrameType.VIDEO_I ? (byte) 0x65 : (byte) 0x41,
                (byte) value
        });
    }

    private static MediaFrame audio(long timestamp, int value) {
        byte[] payload = new byte[160];
        java.util.Arrays.fill(payload, (byte) value);
        return new MediaFrame(MediaFrameType.AUDIO, timestamp, payload);
    }

    private static SimulatorConfig config() {
        SimulatorConfig source = SimulatorConfig.defaults();
        return new SimulatorConfig(
                "localhost",
                65_000,
                source.version(),
                source.mobileNo(),
                source.deviceId(),
                source.channel(),
                source.registration(),
                source.ffmpegPath(),
                "Synthetic Camera",
                "Synthetic Microphone",
                source.mainProfile(),
                source.subProfile(),
                source.previewWidth(),
                source.previewHeight(),
                source.previewFps(),
                source.maxPayloadBytes());
    }

    private static final class RecordingCaptureBackend implements CaptureBackend {
        private final List<CaptureRun> captureRuns = new CopyOnWriteArrayList<>();
        private final List<PreviewRun> previewRuns = new CopyOnWriteArrayList<>();
        private final AtomicInteger activeHandles = new AtomicInteger();
        private final AtomicInteger maximumActiveHandles = new AtomicInteger();

        @Override
        public CaptureHandle startPreview(SimulatorConfig config, Callbacks callbacks) {
            RecordingHandle handle = newHandle();
            previewRuns.add(new PreviewRun(callbacks, handle));
            return handle;
        }

        @Override
        public CaptureHandle startCapture(
                SimulatorConfig config,
                VideoProfile profile,
                boolean includeAudio,
                Callbacks callbacks) {
            RecordingHandle handle = newHandle();
            captureRuns.add(new CaptureRun(profile, includeAudio, callbacks, handle));
            return handle;
        }

        private RecordingHandle newHandle() {
            int active = activeHandles.incrementAndGet();
            maximumActiveHandles.accumulateAndGet(active, Math::max);
            return new RecordingHandle(activeHandles::decrementAndGet);
        }

        private CaptureRun latestCapture() {
            return captureRuns.getLast();
        }

        private List<PreviewRun> previewRuns() {
            return List.copyOf(previewRuns);
        }

        private int maximumActiveHandles() {
            return maximumActiveHandles.get();
        }
    }

    private static final class BlockingCloseCaptureBackend implements CaptureBackend {
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);

        @Override
        public CaptureHandle startPreview(SimulatorConfig config, Callbacks callbacks) {
            return () -> { };
        }

        @Override
        public CaptureHandle startCapture(
                SimulatorConfig config,
                VideoProfile profile,
                boolean includeAudio,
                Callbacks callbacks) {
            AtomicBoolean closed = new AtomicBoolean();
            return () -> {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                closeEntered.countDown();
                try {
                    allowClose.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            };
        }
    }

    private record CaptureRun(
            VideoProfile profile,
            boolean includeAudio,
            CaptureBackend.Callbacks callbacks,
            RecordingHandle handle) {
        private void emit(MediaFrame frame) {
            callbacks.media().accept(frame);
        }

        private void fail(Throwable failure) {
            callbacks.failure().accept(failure);
        }
    }

    private record PreviewRun(CaptureBackend.Callbacks callbacks, RecordingHandle handle) {
    }

    private static final class RecordingHandle implements CaptureBackend.CaptureHandle {
        private final Runnable onClose;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RecordingHandle(Runnable onClose) {
            this.onClose = onClose;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                onClose.run();
            }
        }

        private boolean closed() {
            return closed.get();
        }
    }

    private static final class RecordingWriterFactory implements MediaController.WriterFactory {
        private final List<WriterRun> runs = new CopyOnWriteArrayList<>();
        private final AtomicBoolean failNextWrite = new AtomicBoolean();

        @Override
        public Jt1078TcpWriter connect(MediaTarget target, SimulatorConfig config) {
            TrackingOutputStream output = new TrackingOutputStream(failNextWrite.getAndSet(false));
            Jt1078TcpWriter writer = Jt1078TcpWriter.forOutput(
                    output,
                    config.mobileNo(),
                    config.channel(),
                    config.maxPayloadBytes(),
                    0);
            runs.add(new WriterRun(target, output, writer));
            return writer;
        }

        private void failNextWrite() {
            failNextWrite.set(true);
        }

        private WriterRun latest() {
            return runs.getLast();
        }

        private List<WriterRun> runs() {
            return List.copyOf(runs);
        }
    }

    private record WriterRun(MediaTarget target, TrackingOutputStream output, Jt1078TcpWriter writer) {
    }

    private static final class TrackingOutputStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final boolean failWrites;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TrackingOutputStream(boolean failWrites) {
            this.failWrites = failWrites;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            ensureWritable();
            bytes.write(value);
        }

        @Override
        public synchronized void write(byte[] value, int offset, int length) throws IOException {
            ensureWritable();
            bytes.write(value, offset, length);
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private synchronized int size() {
            return bytes.size();
        }

        private synchronized byte[] bytes() {
            return bytes.toByteArray();
        }

        private boolean closed() {
            return closed.get();
        }

        private void ensureWritable() throws IOException {
            if (failWrites) {
                throw new IOException("synthetic media socket failure");
            }
            if (closed.get()) {
                throw new IOException("media output is closed");
            }
        }
    }

    private static final class RecordingListener implements MediaListener {
        private final List<String> errorContexts = new CopyOnWriteArrayList<>();

        @Override
        public void onError(String context, Throwable error) {
            errorContexts.add(context);
        }

        private List<String> errorContexts() {
            return List.copyOf(errorContexts);
        }
    }
}
