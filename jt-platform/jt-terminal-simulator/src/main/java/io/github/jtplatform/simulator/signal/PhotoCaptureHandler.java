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
    /** 最近若干条 FFmpeg 诊断，失败时并入异常消息方便排查 */
    private final java.util.Deque<String> recentDiagnostics = new java.util.ArrayDeque<>();
    private static final int DIAGNOSTIC_TAIL = 5;

    public PhotoCaptureHandler(SimulatorConfig config, Consumer<String> diagnostics) {
        this.config = Objects.requireNonNull(config, "config");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        // 多媒体 ID 用时间戳起步，避免与历史 ID 撞车
        this.mediaIdSequence = new AtomicInteger((int) (System.currentTimeMillis() & 0x7FFF_FFFF));
    }

    private void rememberDiagnostic(String line) {
        if (recentDiagnostics.size() >= DIAGNOSTIC_TAIL) {
            recentDiagnostics.removeFirst();
        }
        recentDiagnostics.addLast(line);
        diagnostics.accept(line);
    }

    private String diagnosticTail() {
        return String.join(" | ", recentDiagnostics);
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
        // 没配摄像头就合成一张，而不是让整条拍照链路在这里断掉。模拟器的定位是「不需要真实
        // 硬件也能跑通端到端」——行程模拟同样不需要真实 GPS。
        if (camera.isBlank()) {
            return synthesize(count, resolutionCode);
        }
        Path executable = resolveFfmpeg();
        int[] size = resolutionSize(resolutionCode);

        Path directory = Files.createTempDirectory("jt-photo-");
        try {
            // dshow 输入参数与视频推流保持一致（thread_queue_size/rtbufsize），
            // 且不要把 -framerate 作为输入选项——dshow 不接受这种设置方式，
            // 会导致「Could not set video options」后打开输入失败。
            List<String> command = List.of(
                    executable.toString(),
                    "-hide_banner", "-nostdin", "-loglevel", "warning",
                    "-thread_queue_size", "1024",
                    "-f", "dshow",
                    "-rtbufsize", "256M",
                    "-i", "video=" + camera,
                    "-frames:v", Integer.toString(count),
                    "-vf", "scale=" + size[0] + ":" + size[1],
                    "-q:v", "3",
                    "-f", "image2",
                    directory.resolve("photo-%02d.jpg").toString());

            FfmpegProcess process = FfmpegProcess.start(command, this::rememberDiagnostic);
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
                String tail = diagnosticTail();
                throw new IOException("ffmpeg exited with code " + exit + " while capturing photos"
                        + (tail.isBlank() ? "" : "; ffmpeg: " + tail));
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

    /**
     * 合成路径：不碰摄像头、不碰 FFmpeg，直接画出 JPEG。
     *
     * <p>返回的字节与真实抓拍走完全相同的后续流程——{@code SignalClient.uploadPhoto} 的分包上传
     * 与 T0805 应答一个字都不用改，已有的 {@code PhotoSubpackageRoundTripTest} 继续守着那条路径。
     */
    private List<Photo> synthesize(int count, int resolutionCode) throws IOException {
        int[] size = resolutionSize(resolutionCode);
        String plate = config.registration().plateNo();
        List<Photo> photos = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            byte[] jpeg = SyntheticPhoto.render(
                    size[0], size[1],
                    plate == null || plate.isBlank() ? config.deviceId() : plate,
                    config.channel(), index, count);
            photos.add(new Photo(nextMediaId(), jpeg));
        }
        diagnostics.accept("未配置摄像头，已合成 " + count + " 张 "
                + size[0] + "×" + size[1] + " 模拟照片");
        return photos;
    }

    /** 当前抓拍走的是真实摄像头还是合成图。界面用它给出状态提示，免得用户以为摄像头没生效。 */
    public boolean synthesizing() {
        return config.cameraName().isBlank();
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
