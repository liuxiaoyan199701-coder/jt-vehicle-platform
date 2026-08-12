package io.github.jtplatform.simulator.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FfmpegCommandBuilderTest {
    private static final Path FFMPEG = Path.of("ffmpeg.exe");
    private static final VideoSettings VIDEO = new VideoSettings(1280, 720, 25, 2_000, 2);
    private static final PreviewSettings PREVIEW = new PreviewSettings(640, 360, 5, 5);

    @Test
    void buildsSingleProcessAudioVideoFanOutWithoutShellQuoting() {
        FfmpegOutputTargets outputs = new FfmpegOutputTargets(
                URI.create("tcp://127.0.0.1:41001"),
                Optional.of(URI.create("tcp://127.0.0.1:41002")),
                URI.create("tcp://127.0.0.1:41003"));

        List<String> command = new FfmpegCommandBuilder().capture(
                FFMPEG,
                CaptureDevices.audioVideo("集成 摄像头", "麦克风 (USB)"),
                VIDEO,
                PREVIEW,
                outputs);

        assertTrue(command.contains("video=集成 摄像头:audio=麦克风 (USB)"));
        assertFalse(command.stream().anyMatch(argument -> argument.contains("\"集成 摄像头\"")));
        assertTrue(command.contains("libx264"));
        assertTrue(command.contains("aud=1:repeat-headers=1:bframes=0"));
        assertTrue(command.contains("pcm_alaw"));
        assertTrue(command.contains("tcp://127.0.0.1:41001"));
        assertTrue(command.contains("tcp://127.0.0.1:41002"));
        assertTrue(command.contains("tcp://127.0.0.1:41003"));
        assertEquals("50", valueAfter(command, "-g"));
    }

    @Test
    void videoOnlyCaptureOmitsAudioMapAndEncoder() {
        FfmpegOutputTargets outputs = new FfmpegOutputTargets(
                URI.create("tcp://127.0.0.1:42001"), Optional.empty(),
                URI.create("tcp://127.0.0.1:42003"));

        List<String> command = new FfmpegCommandBuilder().capture(
                FFMPEG, CaptureDevices.videoOnly("Camera"), VIDEO, PREVIEW, outputs);

        assertTrue(command.contains("video=Camera"));
        assertFalse(command.contains("pcm_alaw"));
        assertFalse(command.contains("0:a:0"));
    }

    @Test
    void previewUsesMjpegAndConfiguredRateAndSize() {
        List<String> command = new FfmpegCommandBuilder().preview(
                FFMPEG, "Camera", PREVIEW, URI.create("tcp://127.0.0.1:43001"));

        assertTrue(command.contains("mjpeg"));
        assertTrue(command.contains("fps=5,scale=640:360"));
        assertEquals("tcp://127.0.0.1:43001", command.getLast());
    }

    private static String valueAfter(List<String> command, String option) {
        return command.get(command.indexOf(option) + 1);
    }
}
