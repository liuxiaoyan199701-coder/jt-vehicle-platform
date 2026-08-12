package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.TrackPoint;
import io.github.jtconsole.geo.CoordTransform;
import io.github.jtconsole.repository.TrackRepository;
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
    public ApiResponse<Map<String, Object>> query(
            @RequestParam String deviceId,
            @RequestParam String start,
            @RequestParam String end) {
        if (deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        String canonicalId = deviceId.trim();
        List<TrackPoint> points = tracks.findRange(canonicalId, start, end, MAX_POINTS);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", canonicalId);
        result.put("points", points);
        result.put("count", points.size());
        result.put("truncated", points.size() >= MAX_POINTS);
        result.putAll(summarize(points));
        return ApiResponse.ok(result);
    }

    /**
     * 里程优先用设备上报的累计里程差值（更准），设备没报时退化为按坐标累加球面距离。
     */
    private static Map<String, Object> summarize(List<TrackPoint> points) {
        Map<String, Object> summary = new LinkedHashMap<>();
        if (points.isEmpty()) {
            summary.put("distanceKm", 0.0D);
            summary.put("maxSpeedKph", 0.0D);
            summary.put("avgSpeedKph", 0.0D);
            return summary;
        }

        double maxSpeed = 0.0D;
        double speedSum = 0.0D;
        int speedCount = 0;
        for (TrackPoint point : points) {
            if (point.speedKph() != null) {
                maxSpeed = Math.max(maxSpeed, point.speedKph());
                speedSum += point.speedKph();
                speedCount++;
            }
        }

        Double firstMileage = points.getFirst().mileage();
        Double lastMileage = points.getLast().mileage();
        double distanceKm;
        if (firstMileage != null && lastMileage != null && lastMileage >= firstMileage) {
            distanceKm = lastMileage - firstMileage;
        } else {
            double meters = 0.0D;
            for (int i = 1; i < points.size(); i++) {
                TrackPoint previous = points.get(i - 1);
                TrackPoint current = points.get(i);
                meters += CoordTransform.distanceMeters(
                        previous.lat(), previous.lng(), current.lat(), current.lng());
            }
            distanceKm = meters / 1000.0D;
        }

        summary.put("distanceKm", round(distanceKm));
        summary.put("maxSpeedKph", round(maxSpeed));
        summary.put("avgSpeedKph", speedCount == 0 ? 0.0D : round(speedSum / speedCount));
        summary.put("startTime", points.getFirst().deviceTime());
        summary.put("endTime", points.getLast().deviceTime());
        return summary;
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
