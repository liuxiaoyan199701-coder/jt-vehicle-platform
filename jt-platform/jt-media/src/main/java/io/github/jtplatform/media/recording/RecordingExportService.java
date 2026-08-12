package io.github.jtplatform.media.recording;

import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RecordingExportService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingExportService.class);
    private static final byte[] ANNEX_B_START_CODE = {0, 0, 0, 1};
    private static final int LOG_TAIL_BYTES = 4 * 1024;

    private final RecordingSearchService searchService;
    private final Path configuredExportRoot;
    private final String ffmpegCommand;
    private final Duration timeout;
    private final RecordingExportProcessRunner processRunner;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final Set<CompletableFuture<RecordingExportResult>> activeExports =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RecordingExportService(
            RecordingSearchService searchService,
            RecordingProperties properties) {
        this(searchService, properties, new FfmpegProcessRunner());
    }

    RecordingExportService(
            RecordingSearchService searchService,
            RecordingProperties properties,
            RecordingExportProcessRunner processRunner) {
        this(
                searchService,
                properties,
                boundedExecutor(properties),
                processRunner,
                true);
    }

    RecordingExportService(
            RecordingSearchService searchService,
            RecordingProperties properties,
            ExecutorService executor,
            RecordingExportProcessRunner processRunner,
            boolean ownsExecutor) {
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        Objects.requireNonNull(properties, "properties").validate();
        this.configuredExportRoot = properties.getExportRoot().toAbsolutePath().normalize();
        this.ffmpegCommand = properties.getFfmpegCommand();
        this.timeout = properties.getExportTimeout();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.ownsExecutor = ownsExecutor;
    }

    public CompletableFuture<RecordingExportResult> export(RecordingPlaybackRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("RecordingExportService is closed"));
        }

        CompletableFuture<RecordingExportResult> completion = new CompletableFuture<>();
        AtomicReference<Future<?>> submittedTask = new AtomicReference<>();
        activeExports.add(completion);
        completion.whenComplete((ignored, failure) -> {
            activeExports.remove(completion);
            if (completion.isCancelled()) {
                Future<?> task = submittedTask.get();
                if (task != null) {
                    task.cancel(true);
                }
            }
        });

        try {
            Future<?> task = executor.submit(() -> runExport(request, completion));
            submittedTask.set(task);
            if (completion.isCancelled()) {
                task.cancel(true);
            }
        } catch (RejectedExecutionException rejected) {
            completion.completeExceptionally(new IllegalStateException(
                    closed.get()
                            ? "RecordingExportService is closed"
                            : "Recording export queue is full",
                    rejected));
        }
        return completion;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (CompletableFuture<RecordingExportResult> export : List.copyOf(activeExports)) {
            export.cancel(true);
        }
        activeExports.clear();
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    int activeExportCount() {
        return activeExports.size();
    }

    private void runExport(
            RecordingPlaybackRequest request,
            CompletableFuture<RecordingExportResult> completion) {
        if (completion.isDone()) {
            return;
        }
        try {
            completion.complete(exportBlocking(request));
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
        }
    }

    private RecordingExportResult exportBlocking(RecordingPlaybackRequest request)
            throws RecordingExportException {
        try {
            if (searchService.search(
                    request.streamKey(),
                    request.startTimestampUs(),
                    request.endTimestampUs()).isEmpty()) {
                throw new RecordingExportException(
                        "No recording is available in the requested range for "
                                + request.streamKey().externalId());
            }

            ExportTarget target = createTarget(request);
            boolean completed = false;
            try {
                ExportMedia media = extractMedia(request, target);
                List<String> command = ffmpegCommand(media, target.outputPart());
                int exitCode = processRunner.run(
                        command, target.workDirectory(), timeout, target.processLog());
                if (exitCode != 0) {
                    throw new RecordingExportException(
                            "ffmpeg export failed with exit code " + exitCode
                                    + logSuffix(target.processLog()));
                }
                if (!Files.isRegularFile(target.outputPart(), LinkOption.NOFOLLOW_LINKS)
                        || Files.size(target.outputPart()) <= 0) {
                    throw new RecordingExportException(
                            "ffmpeg completed without producing a non-empty MP4 file");
                }

                long size = Files.size(target.outputPart());
                moveAtomically(target.outputPart(), target.outputFile());
                completed = true;
                return new RecordingExportResult(
                        target.outputFile(),
                        size,
                        request.startTimestampUs(),
                        request.endTimestampUs());
            } finally {
                cleanup(target, completed);
            }
        } catch (RecordingExportException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RecordingExportException("Recording export was interrupted", interrupted);
        } catch (IOException | RuntimeException failure) {
            throw new RecordingExportException(
                    "Unable to export recording for " + request.streamKey().externalId(), failure);
        }
    }

    private ExportTarget createTarget(RecordingPlaybackRequest request) throws IOException {
        Files.createDirectories(configuredExportRoot);
        Path exportRoot = configuredExportRoot.toRealPath();
        Path requestedDirectory = exportRoot
                .resolve(RecordingPathEncoder.encode(request.streamKey().deviceId()))
                .resolve(Integer.toString(request.streamKey().channel()))
                .resolve(request.streamKey().streamKind().wireValue())
                .normalize();
        requireDescendant(exportRoot, requestedDirectory, "export directory");
        Files.createDirectories(requestedDirectory);
        Path outputDirectory = requestedDirectory.toRealPath();
        requireDescendant(exportRoot, outputDirectory, "resolved export directory");

        String exportId = UUID.randomUUID().toString().replace("-", "");
        Path workDirectory = outputDirectory.resolve("." + exportId + ".work").normalize();
        Path outputPart = outputDirectory.resolve("." + exportId + ".part.mp4").normalize();
        Path outputFile = outputDirectory.resolve(
                request.startTimestampUs() + "-" + request.endTimestampUs()
                        + "-" + exportId.substring(0, 12) + ".mp4").normalize();
        requireDescendant(outputDirectory, workDirectory, "export work directory");
        requireDescendant(outputDirectory, outputPart, "temporary export file");
        requireDescendant(outputDirectory, outputFile, "export file");
        Files.createDirectory(workDirectory);
        return new ExportTarget(
                workDirectory,
                workDirectory.resolve("video.es"),
                workDirectory.resolve("audio.es"),
                workDirectory.resolve("ffmpeg.log"),
                outputPart,
                outputFile);
    }

    private ExportMedia extractMedia(
            RecordingPlaybackRequest request,
            ExportTarget target) throws IOException, InterruptedException {
        try (RecordingPlaybackCursor cursor = RecordingPlaybackCursor.open(
                        searchService,
                        request.streamKey(),
                        request.startTimestampUs(),
                        request.endTimestampUs());
                ExportMediaWriter writer = new ExportMediaWriter(
                        target.videoInput(), target.audioInput())) {
            MediaFrame frame;
            while ((frame = cursor.next()) != null) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("recording export interrupted while reading frames");
                }
                writer.accept(frame);
            }
            return writer.finish();
        }
    }

    private List<String> ffmpegCommand(ExportMedia media, Path outputPart) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-nostdin");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-y");
        command.add("-fflags");
        command.add("+genpts");

        int nextInput = 0;
        int videoInput = -1;
        int audioInput = -1;
        long firstTimestampUs = media.firstTimestampUs();
        if (media.videoCodec() != null) {
            addInputOffset(command, media.firstVideoTimestampUs(), firstTimestampUs);
            command.add("-r");
            command.add(media.frameRate());
            command.add("-f");
            command.add(media.videoCodec() == MediaCodec.H264 ? "h264" : "hevc");
            command.add("-i");
            command.add(media.videoInput().toString());
            videoInput = nextInput++;
        }
        if (media.audioCodec() != null) {
            addInputOffset(command, media.firstAudioTimestampUs(), firstTimestampUs);
            command.add("-f");
            if (media.audioCodec() == MediaCodec.AAC) {
                command.add("aac");
            } else {
                command.add("alaw");
                command.add("-ar");
                command.add("8000");
                command.add("-ac");
                command.add("1");
            }
            command.add("-i");
            command.add(media.audioInput().toString());
            audioInput = nextInput;
        }

        if (videoInput >= 0) {
            command.add("-map");
            command.add(videoInput + ":v:0");
            command.add("-c:v");
            command.add("copy");
            if (media.videoCodec() == MediaCodec.H265) {
                command.add("-tag:v");
                command.add("hvc1");
            }
        }
        if (audioInput >= 0) {
            command.add("-map");
            command.add(audioInput + ":a:0");
            command.add("-c:a");
            command.add(media.audioCodec() == MediaCodec.AAC ? "copy" : "aac");
            if (media.audioCodec() == MediaCodec.G711A) {
                command.add("-b:a");
                command.add("64k");
            }
        }
        if (videoInput >= 0 && audioInput >= 0) {
            command.add("-shortest");
        }
        command.add("-map_metadata");
        command.add("-1");
        command.add("-avoid_negative_ts");
        command.add("make_zero");
        command.add("-movflags");
        command.add("+faststart");
        command.add("-f");
        command.add("mp4");
        command.add(outputPart.toString());
        return List.copyOf(command);
    }

    private static void addInputOffset(
            List<String> command,
            long inputTimestampUs,
            long firstTimestampUs) {
        if (inputTimestampUs <= firstTimestampUs) {
            return;
        }
        command.add("-itsoffset");
        command.add(String.format(
                Locale.ROOT, "%.6f", (inputTimestampUs - firstTimestampUs) / 1_000_000.0));
    }

    private static void requireDescendant(Path root, Path candidate, String description)
            throws IOException {
        if (candidate.equals(root) || !candidate.startsWith(root)) {
            throw new IOException(description + " escapes the configured export root");
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Recording export filesystem does not support atomic completion", unsupported);
        }
    }

    private static String logSuffix(Path log) {
        try {
            String tail = readTail(log).trim();
            return tail.isEmpty() ? "" : ": " + tail;
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String readTail(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            int length = (int) Math.min(size, LOG_TAIL_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            long position = size - length;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, position + buffer.position());
                if (read < 0) {
                    break;
                }
            }
            return new String(buffer.array(), StandardCharsets.UTF_8);
        }
    }

    private static void cleanup(ExportTarget target, boolean completed) {
        if (!completed) {
            deleteQuietly(target.outputPart());
            deleteQuietly(target.outputFile());
        }
        deleteTreeQuietly(target.workDirectory());
    }

    private static void deleteTreeQuietly(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            LOGGER.warn("Unable to remove recording export work directory {}", root, failure);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException failure) {
            LOGGER.warn("Unable to remove incomplete recording export {}", path, failure);
        }
    }

    private static ExecutorService boundedExecutor(RecordingProperties properties) {
        Objects.requireNonNull(properties, "properties").validate();
        return new ThreadPoolExecutor(
                properties.getExportConcurrency(),
                properties.getExportConcurrency(),
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getExportQueueCapacity()),
                Thread.ofPlatform().daemon().name("recording-export-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private record ExportTarget(
            Path workDirectory,
            Path videoInput,
            Path audioInput,
            Path processLog,
            Path outputPart,
            Path outputFile) {
    }

    private record ExportMedia(
            Path videoInput,
            Path audioInput,
            MediaCodec videoCodec,
            MediaCodec audioCodec,
            long firstVideoTimestampUs,
            long firstAudioTimestampUs,
            String frameRate) {

        private long firstTimestampUs() {
            if (videoCodec == null) {
                return firstAudioTimestampUs;
            }
            if (audioCodec == null) {
                return firstVideoTimestampUs;
            }
            return Math.min(firstVideoTimestampUs, firstAudioTimestampUs);
        }
    }

    private static final class ExportMediaWriter implements AutoCloseable {
        private final Path videoInput;
        private final Path audioInput;
        private final FrameRateEstimator frameRate = new FrameRateEstimator();

        private OutputStream video;
        private OutputStream audio;
        private MediaCodec videoCodec;
        private MediaCodec audioCodec;
        private long firstVideoTimestampUs = -1;
        private long firstAudioTimestampUs = -1;
        private long videoFrameCount;
        private long audioFrameCount;

        private ExportMediaWriter(Path videoInput, Path audioInput) {
            this.videoInput = videoInput;
            this.audioInput = audioInput;
        }

        private void accept(MediaFrame frame) throws IOException {
            switch (frame.type()) {
                case SPS, PPS, VPS, VIDEO_KEY, VIDEO_DELTA, VIDEO_B -> writeVideo(frame);
                case AUDIO -> writeAudio(frame);
                case AUDIO_CONFIG, TRANSPARENT -> {
                    // Configuration is carried by ADTS for AAC; transparent data is not media.
                }
            }
        }

        private void writeVideo(MediaFrame frame) throws IOException {
            if (frame.codec() != MediaCodec.H264 && frame.codec() != MediaCodec.H265) {
                throw new IOException("Unsupported video codec in recording export: " + frame.codec());
            }
            if (videoCodec != null && videoCodec != frame.codec()) {
                throw new IOException("Video codec changes inside a recording export are not supported");
            }
            byte[] payload = frame.payload();
            if (payload.length == 0) {
                throw new IOException("Recording contains an empty video frame");
            }
            videoCodec = frame.codec();
            if (video == null) {
                video = new BufferedOutputStream(Files.newOutputStream(
                        videoInput, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
            }
            if (!hasAnnexBStartCode(payload)) {
                video.write(ANNEX_B_START_CODE);
            }
            video.write(payload);
            if (!frame.type().parameterSet()) {
                if (firstVideoTimestampUs < 0) {
                    firstVideoTimestampUs = frame.timestamp();
                }
                videoFrameCount++;
                frameRate.sample(frame.timestamp());
            }
        }

        private void writeAudio(MediaFrame frame) throws IOException {
            if (frame.codec() != MediaCodec.AAC && frame.codec() != MediaCodec.G711A) {
                throw new IOException("Unsupported audio codec in recording export: " + frame.codec());
            }
            if (audioCodec != null && audioCodec != frame.codec()) {
                throw new IOException("Audio codec changes inside a recording export are not supported");
            }
            byte[] payload = frame.payload();
            if (payload.length == 0) {
                throw new IOException("Recording contains an empty audio frame");
            }
            if (frame.codec() == MediaCodec.AAC && !hasAdtsHeader(payload)) {
                throw new IOException("AAC recording export requires ADTS-framed audio");
            }
            audioCodec = frame.codec();
            if (audio == null) {
                audio = new BufferedOutputStream(Files.newOutputStream(
                        audioInput, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
            }
            audio.write(payload);
            if (firstAudioTimestampUs < 0) {
                firstAudioTimestampUs = frame.timestamp();
            }
            audioFrameCount++;
        }

        private ExportMedia finish() throws IOException {
            close();
            MediaCodec exportedVideoCodec = videoFrameCount == 0 ? null : videoCodec;
            MediaCodec exportedAudioCodec = audioFrameCount == 0 ? null : audioCodec;
            if (exportedVideoCodec == null && exportedAudioCodec == null) {
                throw new RecordingExportException(
                        "The requested recording range contains no exportable audio or video frames");
            }
            return new ExportMedia(
                    videoInput.toAbsolutePath().normalize(),
                    audioInput.toAbsolutePath().normalize(),
                    exportedVideoCodec,
                    exportedAudioCodec,
                    firstVideoTimestampUs,
                    firstAudioTimestampUs,
                    frameRate.value());
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                if (video != null) {
                    video.close();
                    video = null;
                }
            } catch (IOException closeFailure) {
                failure = closeFailure;
            }
            try {
                if (audio != null) {
                    audio.close();
                    audio = null;
                }
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static boolean hasAnnexBStartCode(byte[] payload) {
            return payload.length >= 3
                    && payload[0] == 0
                    && payload[1] == 0
                    && (payload[2] == 1
                    || (payload.length >= 4 && payload[2] == 0 && payload[3] == 1));
        }

        private static boolean hasAdtsHeader(byte[] payload) {
            return payload.length >= 2
                    && (payload[0] & 0xff) == 0xff
                    && (payload[1] & 0xf0) == 0xf0;
        }
    }

    private static final class FrameRateEstimator {
        private static final double DEFAULT_FRAME_RATE = 25.0;
        private static final double MIN_FRAME_RATE = 1.0;
        private static final double MAX_FRAME_RATE = 120.0;

        private long previousTimestampUs = -1;
        private long totalDeltaUs;
        private long intervals;

        private void sample(long timestampUs) {
            if (previousTimestampUs >= 0 && timestampUs > previousTimestampUs) {
                long delta = timestampUs - previousTimestampUs;
                if (Long.MAX_VALUE - totalDeltaUs >= delta) {
                    totalDeltaUs += delta;
                    intervals++;
                }
            }
            if (timestampUs > previousTimestampUs) {
                previousTimestampUs = timestampUs;
            }
        }

        private String value() {
            double value = intervals == 0 || totalDeltaUs == 0
                    ? DEFAULT_FRAME_RATE
                    : intervals * 1_000_000.0 / totalDeltaUs;
            value = Math.max(MIN_FRAME_RATE, Math.min(MAX_FRAME_RATE, value));
            return String.format(Locale.ROOT, "%.3f", value);
        }
    }
}
