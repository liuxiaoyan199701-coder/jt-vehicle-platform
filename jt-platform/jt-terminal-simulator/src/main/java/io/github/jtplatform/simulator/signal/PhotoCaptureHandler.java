package io.github.jtplatform.simulator.signal;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.media.FfmpegDiscovery;
import io.github.jtplatform.simulator.media.FfmpegProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 处理平台下发的 0x8801 摄像头立即拍摄命令：用 FFmpeg 从本机摄像头抓取单帧 JPEG。
 *
 * <p>只负责「抓照片」这一步；照片的 0x0801 上传与 0x8801 的 T0805 应答由
 * {@code SignalClient} 完成——本类返回的 JPEG 字节就是上传的内容。
 */
public final class PhotoCaptureHandler {

    /** 抓取超时：dshow 打开设备偶尔很慢，但 30 秒足够覆盖正常情况 */
    private static final int CAPTURE_TIMEOUT_SECONDS = 30;

    /** 0x8801 的分辨率编码 → 像素尺寸（JT/T 808-2019 表） */
    private static final int[][] RESOLUTIONS = {
        {},                                        // 0 非法
        {320, 240},                                // 1
        {640, 480},                                // 2
        {800, 600},                                // 3
        {1024, 768},                               // 4
        {176, 144},                                // 5 QCIF
        {352, 288},                                // 6 CIF
        {704, 288},                                // 7 HALF D1
        {704, 576},                                // 8 D1
    };

    private final SimulatorConfig config;
    private final Consumer<String> diagnostics;
    private final AtomicInteger mediaIdSequence;

    public PhotoCaptureHandler(SimulatorConfig config, Consumer<String> diagnostics) {
        this.config = Objects.requireNonNull(config, "config");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        // 多媒体 ID 用时间戳起步，避免与历史 ID 撞车
        this.mediaIdSequence = new AtomicInteger((int) (System.currentTimeMillis() & 0x7FFF_FFFF));
    }

    /**
     * 抓取照片。
     *
     * @param count 张数（1..10，0x8801 command 字段的语义）
     * @param resolutionCode 0x8801 resolution 字段（1..8）
     * @return 按拍摄顺序排列的照片；每张携带生成的媒体 ID
     */
    public List<Photo> capture(int count, int resolutionCode) throws IOException, InterruptedException {
        if (count < 1) {
            throw new IllegalArgumentException("photo count must be at least 1");
        }
        String camera = config.cameraName();
        if (camera.isBlank()) {
            throw new IOException("camera is not configured; cannot capture photos");
        }
        Path executable = resolveFfmpeg();
        int[] size = resolutionSize(resolutionCode);

        Path directory = Files.createTempDirectory("jt-photo-");
        try {
            List<String> command = List.of(
                    executable.toString(),
                    "-hide_banner", "-nostdin", "-loglevel", "warning",
                    "-f", "dshow",
                    "-framerate", "10",
                    "-i", "video=" + camera,
                    "-frames:v", Integer.toString(count),
                    "-vf", "scale=" + size[0] + ":" + size[1],
                    "-q:v", "3",
                    "-f", "image2",
                    directory.resolve("photo-%02d.jpg").toString());

            FfmpegProcess process = FfmpegProcess.start(command, diagnostics);
            Integer exit;
            try {
                exit = process.exit().get(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new IOException("failed to wait for ffmpeg photo capture", failure);
            } catch (java.util.concurrent.TimeoutException timeout) {
                process.close();
                throw new IOException("ffmpeg photo capture timed out after "
                        + CAPTURE_TIMEOUT_SECONDS + "s", timeout);
            }
            if (exit != 0) {
                throw new IOException("ffmpeg exited with code " + exit + " while capturing photos");
            }

            List<Path> files;
            try (var stream = Files.list(directory)) {
                files = stream
                        .filter(file -> file.getFileName().toString().endsWith(".jpg"))
                        .sorted(Comparator.comparing(Path::getFileName, Comparator.naturalOrder()))
                        .toList();
            }
            if (files.isEmpty()) {
                throw new IOException("ffmpeg produced no photo files");
            }

            List<Photo> photos = new ArrayList<>(files.size());
            for (Path file : files) {
                byte[] jpeg = Files.readAllBytes(file);
                photos.add(new Photo(nextMediaId(), jpeg));
            }
            return photos;
        } finally {
            try (var stream = Files.walk(directory)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 临时目录清理失败不影响功能
                    }
                });
            }
        }
    }

    private Path resolveFfmpeg() throws IOException {
        Path configured = config.ffmpegPath().isBlank()
                ? Path.of("ffmpeg")
                : Path.of(config.ffmpegPath());
        Optional<Path> resolved = new FfmpegDiscovery().find(configured);
        return resolved.orElseThrow(() -> new IOException(
                "ffmpeg not found; configure the FFmpeg path in the simulator or add it to PATH"));
    }

    private static int[] resolutionSize(int resolutionCode) {
        if (resolutionCode < 1 || resolutionCode >= RESOLUTIONS.length) {
            return RESOLUTIONS[2];   // 非法编码回退到 640×480
        }
        return RESOLUTIONS[resolutionCode];
    }

    private int nextMediaId() {
        return mediaIdSequence.getAndIncrement();
    }

    /** 一张已抓取的照片及其媒体 ID */
    public record Photo(int mediaId, byte[] jpeg) {
        public Photo {
            Objects.requireNonNull(jpeg, "jpeg");
            if (jpeg.length == 0) {
                throw new IllegalArgumentException("jpeg must not be empty");
            }
        }
    }
}
