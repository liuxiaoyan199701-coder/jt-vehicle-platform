package io.github.jtplatform.media.recording;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.RecordingTimeRange;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingEndToEndTest {
    private static final long START_US = 1_700_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsSearchesPlaysBackAndExportsWithoutBlockingLiveRecording() throws Exception {
        RecordingProperties properties = properties();
        StreamKey recorded = new StreamKey("e2e-device", 1, StreamKind.MAIN);
        writeVideoRecording(properties, recorded);

        RecordingSearchService search = new RecordingSearchService(properties);
        assertEquals(
                List.of(new RecordingTimeRange(START_US, START_US + 30)),
                search.search(recorded, START_US, START_US + 30));

        StreamKey playback = new StreamKey("e2e-device", 1, StreamKind.PLAYBACK);
        List<MediaFrame> frames = new CopyOnWriteArrayList<>();
        List<byte[]> wireFrames = new CopyOnWriteArrayList<>();
        RecordingPlaybackOutput binary = RecordingPlaybackOutput.binary(wireFrames::add);
        try (RecordingPlaybackService service = new RecordingPlaybackService(search)) {
            RecordingPlaybackSession session = service.play(
                    new RecordingPlaybackRequest(playback, START_US + 25, START_US + 30),
                    frame -> {
                        frames.add(frame);
                        binary.onFrame(frame);
                    });
            session.completion().get(5, TimeUnit.SECONDS);
        }

        assertEquals(
                List.of(MediaFrameType.SPS, MediaFrameType.PPS,
                        MediaFrameType.VIDEO_KEY, MediaFrameType.VIDEO_DELTA),
                frames.stream().map(MediaFrame::type).toList());
        assertEquals(START_US + 20, frames.get(2).timestamp());
        assertArrayEquals(new byte[] {'J', 'T', '7', '8'},
                java.util.Arrays.copyOf(wireFrames.getFirst(), 4));

        CountDownLatch exportStarted = new CountDownLatch(1);
        CountDownLatch allowExport = new CountDownLatch(1);
        RecordingExportProcessRunner runner = (command, workingDirectory, timeout, outputLog) -> {
            exportStarted.countDown();
            if (!allowExport.await(5, TimeUnit.SECONDS)) {
                throw new java.io.IOException("test export was not released");
            }
            writeMinimalMp4(Path.of(command.getLast()));
            return 0;
        };

        RecordingExportResult exported;
        try (RecordingExportService exportService = new RecordingExportService(
                search, properties, runner)) {
            var export = exportService.export(new RecordingPlaybackRequest(
                    recorded, START_US + 25, START_US + 30));
            assertTrue(exportStarted.await(5, TimeUnit.SECONDS));

            StreamKey liveDuringExport = new StreamKey("live-during-export", 2, StreamKind.SUB);
            try (RecordSink liveSink = new RecordSink(properties)) {
                liveSink.accept(new MediaFrame(
                        liveDuringExport,
                        MediaFrameType.AUDIO,
                        MediaCodec.G711A,
                        START_US + 100,
                        new byte[] {1, 2, 3, 4}));
            }
            assertEquals(1, search.search(
                    liveDuringExport, START_US + 100, START_US + 100).size());

            allowExport.countDown();
            exported = export.get(5, TimeUnit.SECONDS);
        } finally {
            allowExport.countDown();
        }

        assertTrue(Files.isRegularFile(exported.outputFile()));
        assertEquals(Files.size(exported.outputFile()), exported.sizeBytes());
    }

    private RecordingProperties properties() {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("recordings"));
        properties.setExportRoot(temporaryDirectory.resolve("exports"));
        properties.setRealtimeEnabled(true);
        properties.setContinuousEnabled(true);
        properties.setSegmentDuration(Duration.ofSeconds(30));
        properties.setExportConcurrency(1);
        properties.setExportQueueCapacity(1);
        properties.setExportTimeout(Duration.ofSeconds(10));
        return properties;
    }

    private static void writeVideoRecording(
            RecordingProperties properties,
            StreamKey streamKey) {
        try (RecordSink sink = new RecordSink(properties)) {
            sink.accept(frame(streamKey, MediaFrameType.SPS, START_US - 2,
                    new byte[] {0, 0, 0, 1, 0x67, 0x01}));
            sink.accept(frame(streamKey, MediaFrameType.PPS, START_US - 1,
                    new byte[] {0, 0, 0, 1, 0x68, 0x02}));
            sink.accept(frame(streamKey, MediaFrameType.VIDEO_KEY, START_US,
                    new byte[] {0, 0, 0, 1, 0x65, 0x10}));
            sink.accept(frame(streamKey, MediaFrameType.VIDEO_DELTA, START_US + 10,
                    new byte[] {0, 0, 0, 1, 0x41, 0x11}));
            sink.accept(frame(streamKey, MediaFrameType.VIDEO_KEY, START_US + 20,
                    new byte[] {0, 0, 0, 1, 0x65, 0x20}));
            sink.accept(frame(streamKey, MediaFrameType.VIDEO_DELTA, START_US + 30,
                    new byte[] {0, 0, 0, 1, 0x41, 0x21}));
        }
    }

    private static MediaFrame frame(
            StreamKey streamKey,
            MediaFrameType type,
            long timestampUs,
            byte[] payload) {
        return new MediaFrame(streamKey, type, MediaCodec.H264, timestampUs, payload);
    }

    private static void writeMinimalMp4(Path output) throws java.io.IOException {
        ByteBuffer header = ByteBuffer.allocate(24);
        header.putInt(24);
        header.put("ftyp".getBytes(StandardCharsets.US_ASCII));
        header.put("isom".getBytes(StandardCharsets.US_ASCII));
        header.putInt(0x200);
        header.put("isom".getBytes(StandardCharsets.US_ASCII));
        header.put("mp41".getBytes(StandardCharsets.US_ASCII));
        Files.write(output, header.array());
    }
}
