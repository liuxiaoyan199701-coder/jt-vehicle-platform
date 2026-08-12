package io.github.jtplatform.media.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingExportServiceTest {
    private static final long START_US = 1_700_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsOnDedicatedExecutorWithExpectedFfmpegCommandAndAtomicOutput() throws Exception {
        RecordingProperties properties = properties();
        properties.setFfmpegCommand("ffmpeg-test");
        StreamKey key = new StreamKey("../unsafe/device", 3, StreamKind.MAIN);
        writeH264(properties, key);
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> runnerThread = new AtomicReference<>();
        AtomicReference<List<String>> capturedCommand = new AtomicReference<>();
        AtomicReference<Path> capturedWorkDirectory = new AtomicReference<>();

        RecordingExportProcessRunner runner = (command, workingDirectory, timeout, outputLog) -> {
            runnerThread.set(Thread.currentThread().getName());
            capturedCommand.set(command);
            capturedWorkDirectory.set(workingDirectory);
            writeMinimalMp4(Path.of(command.getLast()));
            return 0;
        };

        RecordingExportResult result;
        try (RecordingExportService service = new RecordingExportService(
                new RecordingSearchService(properties), properties, runner)) {
            result = service.export(new RecordingPlaybackRequest(
                            key, START_US + 20_000, START_US + 40_000))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(0, service.activeExportCount());
        }

        List<String> command = capturedCommand.get();
        assertEquals("ffmpeg-test", command.getFirst());
        assertTrue(command.containsAll(List.of("-f", "h264", "-c:v", "copy", "+faststart")));
        assertEquals("mp4", command.get(command.size() - 2));
        Path temporaryOutput = Path.of(command.getLast()).toAbsolutePath().normalize();
        assertFalse(result.outputFile().equals(temporaryOutput));
        assertEquals(result.outputFile().getParent(), temporaryOutput.getParent());
        assertFalse(Files.exists(temporaryOutput));
        assertTrue(runnerThread.get().startsWith("recording-export-"));
        assertFalse(runnerThread.get().equals(callerThread));
        assertTrue(Files.isRegularFile(result.outputFile()));
        assertEquals(Files.size(result.outputFile()), result.sizeBytes());
        assertTrue(result.outputFile().startsWith(properties.getExportRoot().toRealPath()));
        assertFalse(result.outputFile().toString().contains("unsafe"));
        assertFalse(Files.exists(capturedWorkDirectory.get()));
        assertEquals(0, filesEndingWith(properties.getExportRoot(), ".part.mp4"));
    }

    @Test
    void transcodesG711aToAacForMp4Compatibility() throws Exception {
        RecordingProperties properties = properties();
        StreamKey key = new StreamKey("audio-device", 1, StreamKind.SUB);
        try (RecordSink sink = new RecordSink(properties)) {
            sink.accept(new MediaFrame(
                    key,
                    MediaFrameType.AUDIO,
                    MediaCodec.G711A,
                    START_US,
                    new byte[] {1, 2, 3, 4}));
        }
        AtomicReference<List<String>> capturedCommand = new AtomicReference<>();
        RecordingExportProcessRunner runner = (command, workingDirectory, timeout, outputLog) -> {
            capturedCommand.set(command);
            writeMinimalMp4(Path.of(command.getLast()));
            return 0;
        };

        try (RecordingExportService service = new RecordingExportService(
                new RecordingSearchService(properties), properties, runner)) {
            service.export(new RecordingPlaybackRequest(key, START_US, START_US))
                    .get(5, TimeUnit.SECONDS);
        }

        List<String> command = capturedCommand.get();
        assertTrue(command.containsAll(List.of(
                "-f", "alaw", "-ar", "8000", "-ac", "1", "-c:a", "aac", "64k")));
    }

    @Test
    void failsClearlyWithoutRecordingAndDoesNotInvokeFfmpeg() throws Exception {
        RecordingProperties properties = properties();
        AtomicInteger invocations = new AtomicInteger();
        RecordingExportProcessRunner runner = (command, workingDirectory, timeout, outputLog) -> {
            invocations.incrementAndGet();
            return 0;
        };

        ExecutionException failure;
        try (RecordingExportService service = new RecordingExportService(
                new RecordingSearchService(properties), properties, runner)) {
            failure = assertThrows(ExecutionException.class, () -> service.export(
                            new RecordingPlaybackRequest(
                                    new StreamKey("missing", 1, StreamKind.MAIN),
                                    START_US,
                                    START_US + 1_000_000))
                    .get(5, TimeUnit.SECONDS));
        }

        RecordingExportException cause = assertInstanceOf(
                RecordingExportException.class, failure.getCause());
        assertTrue(cause.getMessage().contains("No recording is available"));
        assertEquals(0, invocations.get());
        assertFalse(Files.exists(properties.getExportRoot()));
    }

    @Test
    void removesPartialOutputAndWorkFilesWhenFfmpegFails() throws Exception {
        RecordingProperties properties = properties();
        StreamKey key = new StreamKey("failing-device", 1, StreamKind.MAIN);
        writeH264(properties, key);
        AtomicReference<Path> workingDirectory = new AtomicReference<>();
        RecordingExportProcessRunner runner = (command, work, timeout, outputLog) -> {
            workingDirectory.set(work);
            Files.write(Path.of(command.getLast()), new byte[] {1, 2, 3});
            Files.writeString(outputLog, "invalid elementary stream", StandardCharsets.UTF_8);
            return 17;
        };

        ExecutionException failure;
        try (RecordingExportService service = new RecordingExportService(
                new RecordingSearchService(properties), properties, runner)) {
            failure = assertThrows(ExecutionException.class, () -> service.export(
                            new RecordingPlaybackRequest(key, START_US, START_US + 40_000))
                    .get(5, TimeUnit.SECONDS));
        }

        RecordingExportException cause = assertInstanceOf(
                RecordingExportException.class, failure.getCause());
        assertTrue(cause.getMessage().contains("exit code 17"));
        assertTrue(cause.getMessage().contains("invalid elementary stream"));
        assertFalse(Files.exists(workingDirectory.get()));
        assertEquals(0, regularFiles(properties.getExportRoot()));
    }

    @Test
    void closeCancelsAnActiveExportAndInterruptsItsWorker() throws Exception {
        RecordingProperties properties = properties();
        StreamKey key = new StreamKey("slow-device", 1, StreamKind.MAIN);
        writeH264(properties, key);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean runnerInterrupted = new AtomicBoolean();
        RecordingExportProcessRunner runner = (command, workingDirectory, timeout, outputLog) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return 0;
            } catch (InterruptedException failure) {
                runnerInterrupted.set(true);
                interrupted.countDown();
                throw failure;
            }
        };
        RecordingExportService service = new RecordingExportService(
                new RecordingSearchService(properties), properties, runner);

        var export = service.export(
                new RecordingPlaybackRequest(key, START_US, START_US + 40_000));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        service.close();
        service.close();

        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertTrue(runnerInterrupted.get());
        assertTrue(export.isCancelled());
        assertThrows(ExecutionException.class, () -> service.export(
                        new RecordingPlaybackRequest(key, START_US, START_US + 40_000))
                .get(5, TimeUnit.SECONDS));
    }

    @Test
    void rejectsWorkWhenTheBoundedExportQueueIsFull() throws Exception {
        RecordingProperties properties = properties();
        properties.setExportQueueCapacity(1);
        StreamKey key = new StreamKey("queued-device", 1, StreamKind.MAIN);
        writeH264(properties, key);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        RecordingExportProcessRunner runner = (command, workingDirectory, timeout, outputLog) -> {
            if (invocations.getAndIncrement() == 0) {
                firstStarted.countDown();
                release.await();
            }
            writeMinimalMp4(Path.of(command.getLast()));
            return 0;
        };

        try (RecordingExportService service = new RecordingExportService(
                new RecordingSearchService(properties), properties, runner)) {
            RecordingPlaybackRequest request = new RecordingPlaybackRequest(
                    key, START_US, START_US + 40_000);
            var running = service.export(request);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            var queued = service.export(request);
            var rejected = service.export(request);

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> rejected.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("queue is full"));

            release.countDown();
            running.get(5, TimeUnit.SECONDS);
            queued.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertEquals(2, invocations.get());
    }

    @Test
    void validatesExportResourceAndCommandBoundaries() {
        RecordingProperties properties = properties();
        properties.setFfmpegCommand("  ");
        assertThrows(IllegalStateException.class, properties::validate);

        properties = properties();
        properties.setExportConcurrency(0);
        assertThrows(IllegalStateException.class, properties::validate);

        properties = properties();
        properties.setExportQueueCapacity(0);
        assertThrows(IllegalStateException.class, properties::validate);

        properties = properties();
        properties.setExportTimeout(Duration.ofNanos(1));
        assertThrows(IllegalStateException.class, properties::validate);
    }

    private RecordingProperties properties() {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("recordings"));
        properties.setExportRoot(temporaryDirectory.resolve("exports"));
        properties.setRealtimeEnabled(true);
        properties.setSegmentDuration(Duration.ofSeconds(30));
        properties.setExportConcurrency(1);
        properties.setExportQueueCapacity(2);
        properties.setExportTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private static void writeH264(RecordingProperties properties, StreamKey key) {
        try (RecordSink sink = new RecordSink(properties)) {
            sink.accept(frame(key, MediaFrameType.SPS, START_US - 2,
                    new byte[] {0, 0, 0, 1, 0x67, 0x01}));
            sink.accept(frame(key, MediaFrameType.PPS, START_US - 1,
                    new byte[] {0, 0, 0, 1, 0x68, 0x02}));
            sink.accept(frame(key, MediaFrameType.VIDEO_KEY, START_US,
                    new byte[] {0, 0, 0, 1, 0x65, 0x03}));
            sink.accept(frame(key, MediaFrameType.VIDEO_DELTA, START_US + 40_000,
                    new byte[] {0, 0, 0, 1, 0x41, 0x04}));
        }
    }

    private static MediaFrame frame(
            StreamKey key,
            MediaFrameType type,
            long timestampUs,
            byte[] payload) {
        return new MediaFrame(key, type, MediaCodec.H264, timestampUs, payload);
    }

    private static void writeMinimalMp4(Path output) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24);
        header.putInt(24);
        header.put("ftyp".getBytes(StandardCharsets.US_ASCII));
        header.put("isom".getBytes(StandardCharsets.US_ASCII));
        header.putInt(0x200);
        header.put("isom".getBytes(StandardCharsets.US_ASCII));
        header.put("mp41".getBytes(StandardCharsets.US_ASCII));
        Files.write(output, header.array());
    }

    private static long filesEndingWith(Path root, String suffix) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .count();
        }
    }

    private static long regularFiles(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }
}
