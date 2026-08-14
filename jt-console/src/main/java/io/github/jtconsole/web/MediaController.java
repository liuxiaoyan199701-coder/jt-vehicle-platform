package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.repository.MediaRepository;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.List;
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
}
