package io.github.jtplatform.simulator.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FfmpegCommandBuilder {
    public List<String> preview(
            Path executable,
            String camera,
            PreviewSettings settings,
            java.net.URI output) {
        Objects.requireNonNull(settings, "settings");
        List<String> command = base(executable);
        addDirectShowInput(command, CaptureDevices.videoOnly(camera));
        command.addAll(List.of(
                "-map", "0:v:0",
                "-an",
                "-vf", previewFilter(settings),
                "-c:v", "mjpeg",
                "-q:v", Integer.toString(settings.jpegQuality()),
                "-f", "mjpeg",
                requireTarget(output, "output")));
        return List.copyOf(command);
    }

    public List<String> capture(
            Path executable,
            CaptureDevices devices,
            VideoSettings video,
            PreviewSettings preview,
            FfmpegOutputTargets outputs) {
        Objects.requireNonNull(devices, "devices");
        Objects.requireNonNull(video, "video");
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(outputs, "outputs");
        if (devices.microphone().isPresent() != outputs.g711a().isPresent()) {
            throw new IllegalArgumentException("Microphone and G.711A output must either both be present or absent");
        }

        List<String> command = base(executable);
        addDirectShowInput(command, devices);
        addH264Output(command, video, outputs.h264().toString());
        outputs.g711a().ifPresent(target -> addG711aOutput(command, target.toString()));
        addMjpegOutput(command, preview, outputs.mjpeg().toString());
        return List.copyOf(command);
    }

    private static List<String> base(Path executable) {
        Path checked = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        return new ArrayList<>(List.of(
                checked.toString(),
                "-hide_banner",
                "-nostdin",
                "-loglevel", "warning"));
    }

    private static void addDirectShowInput(List<String> command, CaptureDevices devices) {
        String input = "video=" + devices.camera();
        if (devices.microphone().isPresent()) {
            input += ":audio=" + devices.microphone().orElseThrow();
        }
        command.addAll(List.of(
                "-thread_queue_size", "1024",
                "-f", "dshow",
                "-rtbufsize", "256M",
                "-i", input));
    }

    private static void addH264Output(List<String> command, VideoSettings settings, String output) {
        int gopFrames = settings.gopFrames();
        command.addAll(List.of(
                "-map", "0:v:0",
                "-an",
                "-vf", "scale=" + settings.width() + ':' + settings.height(),
                "-r", Integer.toString(settings.frameRate()),
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-tune", "zerolatency",
                "-pix_fmt", "yuv420p",
                "-b:v", settings.bitrateKbps() + "k",
                "-maxrate", settings.bitrateKbps() + "k",
                "-bufsize", Math.multiplyExact(settings.bitrateKbps(), 2) + "k",
                "-g", Integer.toString(gopFrames),
                "-keyint_min", Integer.toString(gopFrames),
                "-sc_threshold", "0",
                "-x264-params", "aud=1:repeat-headers=1:bframes=0",
                "-f", "h264",
                output));
    }

    private static void addG711aOutput(List<String> command, String output) {
        command.addAll(List.of(
                "-map", "0:a:0",
                "-vn",
                "-ac", "1",
                "-ar", "8000",
                "-c:a", "pcm_alaw",
                "-f", "alaw",
                output));
    }

    private static void addMjpegOutput(List<String> command, PreviewSettings settings, String output) {
        command.addAll(List.of(
                "-map", "0:v:0",
                "-an",
                "-vf", previewFilter(settings),
                "-c:v", "mjpeg",
                "-q:v", Integer.toString(settings.jpegQuality()),
                "-f", "mjpeg",
                output));
    }

    private static String previewFilter(PreviewSettings settings) {
        return "fps=" + settings.frameRate() + ",scale=" + settings.width() + ':' + settings.height();
    }

    private static String requireTarget(java.net.URI uri, String name) {
        Objects.requireNonNull(uri, name);
        if (!"tcp".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1) {
            throw new IllegalArgumentException(name + " must be a TCP URI with host and port");
        }
        return uri.toString();
    }
}
