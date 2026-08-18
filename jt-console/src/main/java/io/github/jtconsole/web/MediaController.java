package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.repository.MediaRepository;
import io.github.jtconsole.repository.MediaRepository.MediaFilter;
import io.github.jtconsole.repository.MediaRepository.MediaPage;
import io.github.jtconsole.repository.MediaRepository.MediaTrigger;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 终端上传的多媒体文件列表（拍照结果等）。
 * 二进制文件由网关落盘并直接提供下载，这里只返回元数据与访问地址。
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    /**
     * 「查看该告警时段的抓拍」的时间窗半径。
     *
     * <p>取 90 秒的依据：0x8801 下发到终端回传 0x0801 通常在数十秒内完成（要唤醒摄像头、编码、
     * 分包上传），而报警触发的抓拍是设备自发的、更快。窗口太窄会漏掉上传慢的那张，太宽会把
     * 相邻的另一次抓拍也算进来。**这不是因果关联**——协议没有给出任何能定位到具体告警的字段，
     * 见 {@code MediaFile} 的类注释。
     */
    private static final int ALARM_WINDOW_SECONDS = 90;

    private static final int MAX_PAGE_SIZE = 60;

    private final MediaRepository media;

    public MediaController(MediaRepository media) {
        this.media = media;
    }

    @GetMapping("/recent")
    @RequirePermission(Permissions.MEDIA_LIST)
    public ApiResponse<List<MediaFile>> recent(
            @RequestParam String deviceId,
            @RequestParam(defaultValue = "20") int limit,
            DataScope scope) {
        if (deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        return ApiResponse.ok(media.findRecentByDevice(
                deviceId.trim(), Math.min(Math.max(limit, 1), 100), scope));
    }

    /**
     * 多媒体检索。所有筛选项皆可选——不给 deviceId 就是跨车辆查，这正是多媒体页存在的理由。
     */
    @GetMapping
    @RequirePermission(Permissions.MEDIA_LIST)
    public ApiResponse<MediaPage> search(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String fileType,
            @RequestParam(required = false) Integer channelId,
            @RequestParam(required = false) String trigger,
            @RequestParam(required = false) Boolean locatedOnly,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "24") int pageSize,
            DataScope scope) {
        MediaFilter filter = new MediaFilter(
                deviceId, fileType, channelId, parseTrigger(trigger), locatedOnly, start, end,
                Math.max(page, 1),
                Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE));
        return ApiResponse.ok(media.search(filter, scope));
    }

    /**
     * 某台车在给定时刻前后的抓拍。
     *
     * <p>端点名刻意是 {@code /around} 而不是 {@code /by-alarm}：返回的是时间邻近的照片，
     * 不是「属于这条告警」的照片。命名如实，调用方才不会误当因果。
     */
    @GetMapping("/around")
    @RequirePermission(Permissions.MEDIA_LIST)
    public ApiResponse<List<MediaFile>> around(
            @RequestParam String deviceId,
            @RequestParam String at,
            @RequestParam(defaultValue = "20") int limit,
            DataScope scope) {
        if (deviceId.isBlank() || at.isBlank()) {
            throw new IllegalArgumentException("deviceId 与 at 不能为空");
        }
        Window window = Window.around(at.trim(), ALARM_WINDOW_SECONDS);
        return ApiResponse.ok(media.findByDeviceWindow(
                deviceId.trim(), window.from(), window.to(),
                Math.min(Math.max(limit, 1), 100), scope));
    }

    private static MediaTrigger parseTrigger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MediaTrigger.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("trigger 只能是 manual 或 alarm");
        }
    }

    /**
     * 时间窗。
     *
     * <p>刻意用 {@link java.time.OffsetDateTime} 解析而不是字符串加减：{@code captured_at} 存的是
     * 带偏移的 ISO 串，直接拼字符串在跨分钟、跨小时、跨日时都会算错，而那正是最需要它对的时候。
     */
    private record Window(String from, String to) {

        static Window around(String at, int radiusSeconds) {
            java.time.OffsetDateTime moment = parse(at);
            return new Window(
                    moment.minusSeconds(radiusSeconds).toString(),
                    moment.plusSeconds(radiusSeconds).toString());
        }

        private static java.time.OffsetDateTime parse(String at) {
            String normalized = at.replace(' ', 'T');
            try {
                return java.time.OffsetDateTime.parse(normalized);
            } catch (java.time.format.DateTimeParseException noOffset) {
                // 不带偏移的输入按东八区解释，与平台其余时间口径一致。
                return java.time.LocalDateTime.parse(normalized)
                        .atOffset(java.time.ZoneOffset.ofHours(8));
            }
        }
    }
}
