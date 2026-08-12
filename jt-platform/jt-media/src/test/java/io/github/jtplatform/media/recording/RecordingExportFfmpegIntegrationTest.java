package io.github.jtplatform.media.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingExportFfmpegIntegrationTest {
    private static final long START_US = 1_700_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsRecordedH264AsAPlayableMp4() throws Exception {
        String configuredFfmpeg = System.getProperty("jt.test.ffmpeg", "").trim();
        assumeTrue(!configuredFfmpeg.isEmpty(),
                "Set -Djt.test.ffmpeg=/path/to/ffmpeg to run the real export test");
        Path ffmpeg = Path.of(configuredFfmpeg).toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(ffmpeg), "Configured ffmpeg executable does not exist");
        Path ffprobe = ffmpeg.resolveSibling(executableName("ffprobe"));
        assumeTrue(Files.isRegularFile(ffprobe), "ffprobe must be next to ffmpeg");

        Path elementaryStream = temporaryDirectory.resolve("source.h264");
        ProcessResult generation = run(List.of(
                ffmpeg.toString(),
                "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=64x64:rate=5",
                "-frames:v", "5", "-c:v", "libx264", "-preset", "ultrafast",
                "-tune", "zerolatency",
                "-x264-params", "keyint=5:min-keyint=5:scenecut=0:repeat-headers=1",
                "-f", "h264", elementaryStream.toString()));
        assertEquals(0, generation.exitCode(), generation.output());

        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(temporaryDirectory.resolve("recordings"));
        properties.setExportRoot(temporaryDirectory.resolve("exports"));
        properties.setRealtimeEnabled(true);
        properties.setContinuousEnabled(true);
        properties.setFfmpegCommand(ffmpeg.toString());
        properties.setExportConcurrency(1);
        properties.setExportQueueCapacity(1);
        properties.setExportTimeout(Duration.ofSeconds(30));

        StreamKey streamKey = new StreamKey("ffmpeg-device", 1, StreamKind.MAIN);
        long endTimestampUs = recordH264(properties, streamKey, elementaryStream);

        RecordingExportResult exported;
        try (RecordingExportService service = new RecordingExportService(
                new RecordingSearchService(properties), properties)) {
            exported = service.export(new RecordingPlaybackRequest(
                            streamKey, START_US, endTimestampUs))
                    .get(30, TimeUnit.SECONDS);
        }

        assertTrue(Files.isRegularFile(exported.outputFile()));
        assertTrue(exported.sizeBytes() > 0);
        ProcessResult probe = run(List.of(
                ffprobe.toString(), "-v", "error",
                "-show_entries", "format=format_name:stream=codec_name,codec_type",
                "-of", "json", exported.outputFile().toString()));
        assertEquals(0, probe.exitCode(), probe.output());
        assertTrue(probe.output().contains("\"codec_name\": \"h264\""), probe.output());
        assertTrue(probe.output().contains("\"codec_type\": \"video\""), probe.output());
        assertTrue(probe.output().contains("mp4"), probe.output());
    }

    private static long recordH264(
            RecordingProperties properties,
            StreamKey streamKey,
            Path elementaryStream) throws IOException {
        List<byte[]> nalUnits = splitAnnexB(Files.readAllBytes(elementaryStream));
        long timestampUs = START_US;
        boolean sawKeyFrame = false;
        try (RecordSink sink = new RecordSink(properties)) {
            for (byte[] nalUnit : nalUnits) {
                int type = nalType(nalUnit);
                MediaFrameType frameType = switch (type) {
                    case 7 -> MediaFrameType.SPS;
                    case 8 -> MediaFrameType.PPS;
                    case 5 -> MediaFrameType.VIDEO_KEY;
                    case 1 -> MediaFrameType.VIDEO_DELTA;
                    default -> null;
                };
                if (frameType == null) {
                    continue;
                }
                sink.accept(new MediaFrame(
                        streamKey, frameType, MediaCodec.H264, timestampUs, nalUnit));
                if (!frameType.parameterSet()) {
                    sawKeyFrame |= frameType == MediaFrameType.VIDEO_KEY;
                    timestampUs += 200_000L;
                }
            }
        }
        assertTrue(sawKeyFrame, "Generated H.264 stream did not contain an IDR frame");
        return timestampUs - 200_000L;
    }

    private static List<byte[]> splitAnnexB(byte[] bytes) {
        List<Integer> starts = new ArrayList<>();
        for (int index = 0; index < bytes.length - 2; index++) {
            int length = startCodeLength(bytes, index);
            if (length > 0) {
                starts.add(index);
                index += length - 1;
            }
        }
        List<byte[]> result = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            int start = starts.get(index);
            int end = index + 1 < starts.size() ? starts.get(index + 1) : bytes.length;
            result.add(java.util.Arrays.copyOfRange(bytes, start, end));
        }
        return result;
    }

    private static int nalType(byte[] nalUnit) {
        int header = startCodeLength(nalUnit, 0);
        if (header == 0 || header >= nalUnit.length) {
            throw new IllegalArgumentException("Invalid Annex-B NAL unit");
        }
        return nalUnit[header] & 0x1f;
    }

    private static int startCodeLength(byte[] bytes, int offset) {
        if (offset + 2 < bytes.length
                && bytes[offset] == 0
                && bytes[offset + 1] == 0
                && bytes[offset + 2] == 1) {
            return 3;
        }
        if (offset + 3 < bytes.length
                && bytes[offset] == 0
                && bytes[offset + 1] == 0
                && bytes[offset + 2] == 0
                && bytes[offset + 3] == 1) {
            return 4;
        }
        return 0;
    }

    private static ProcessResult run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("Process timed out: " + command.getFirst());
        }
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private static String executableName(String baseName) {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .contains("win") ? baseName + ".exe" : baseName;
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
