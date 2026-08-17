package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.TrackPoint;
import io.github.jtconsole.operations.TrackSummary;
import io.github.jtconsole.geo.CoordTransform;
import io.github.jtconsole.repository.TrackRepository;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tracks")
public class TrackController {

    /** 单次查询返回的最大点数，防止大时间跨度把前端和内存打爆 */
    private static final int MAX_POINTS = 20000;

    private final TrackRepository tracks;

    public TrackController(TrackRepository tracks) {
        this.tracks = tracks;
    }

    /**
     * 按设备与时间范围查询轨迹。
     *
     * @param start 起始时间，格式需与入库的 device_time 一致（无时区本地时间，如
     *              {@code 2026-08-11T00:00:00}）
     */
    @GetMapping
    @RequirePermission(Permissions.TRACK_VIEW)
    public ApiResponse<Map<String, Object>> query(
            @RequestParam String deviceId,
            @RequestParam String start,
            @RequestParam String end,
            DataScope scope) {
        if (deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        String canonicalId = deviceId.trim();
        List<TrackPoint> points = tracks.findRange(canonicalId, start, end, MAX_POINTS, scope);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", canonicalId);
        result.put("points", points);
        result.put("count", points.size());
        result.put("truncated", points.size() >= MAX_POINTS);
        result.putAll(TrackSummary.of(points));
        return ApiResponse.ok(result);
    }

}
