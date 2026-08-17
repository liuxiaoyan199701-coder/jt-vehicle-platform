package io.github.jtconsole.operations;

import io.github.jtconsole.domain.TrackPoint;
import io.github.jtconsole.geo.CoordTransform;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一段轨迹的汇总指标。
 *
 * <p>从 {@code TrackController} 抽出来共享，是为了让轨迹回放页与 AI 助手报出同一个里程数字。
 * 如果各算各的，用户会看到「页面说 42 公里，AI 说 43 公里」——那种不一致比两边都错更伤信任。
 */
public final class TrackSummary {

    private TrackSummary() {
    }

    /**
     * 里程优先用设备上报的累计里程差值（更准），设备没报时退化为按坐标累加球面距离。
     *
     * @param points 已按设备时间升序排列的轨迹点
     */
    public static Map<String, Object> of(List<TrackPoint> points) {
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
