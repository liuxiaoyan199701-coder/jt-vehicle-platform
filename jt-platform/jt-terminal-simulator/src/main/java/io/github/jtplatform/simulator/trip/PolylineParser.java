package io.github.jtplatform.simulator.trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 解析地图服务返回的折线串：{@code 经度,纬度;经度,纬度;…}。
 *
 * <p><b>经度在前</b>。这与「纬度,经度」的日常书写顺序相反，是本模块最容易写反的地方；写反了不会
 * 报错，只会让车跑到地球另一侧。
 *
 * <p>解析结果保持字符串里的坐标系不变——来自地图服务就是加密坐标系。转换到原始坐标系的时机是
 * 构造 {@link Route} 之前，只做一次。
 */
public final class PolylineParser {

    private PolylineParser() {
    }

    /**
     * 解析单条折线串。无法解析的点直接跳过，不抛异常：路径规划返回几百个点，
     * 为其中一个畸形点放弃整条路线不划算，而少一个点在几十米尺度上看不出来。
     */
    public static List<GeoPoint> parse(String polyline) {
        Objects.requireNonNull(polyline, "polyline");
        List<GeoPoint> points = new ArrayList<>();
        for (String pair : polyline.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int comma = trimmed.indexOf(',');
            if (comma <= 0 || comma == trimmed.length() - 1) {
                continue;
            }
            try {
                double lng = Double.parseDouble(trimmed.substring(0, comma));
                double lat = Double.parseDouble(trimmed.substring(comma + 1));
                if (Double.isFinite(lat) && Double.isFinite(lng)) {
                    points.add(new GeoPoint(lat, lng));
                }
            } catch (NumberFormatException ignored) {
                // 畸形点跳过，理由见方法注释。
            }
        }
        return points;
    }

    /**
     * 依次解析多条折线串并首尾相接，去掉接缝处的重复点。
     *
     * <p>路径规划把整条路线拆成若干分段，每段自带折线，而**相邻两段的折线首尾是同一个点**。
     * 不去重就会留下零长度段，其方位角是 {@code atan2(0, 0)} 恒为 0——车头会在每个接缝瞬间跳向正北。
     */
    public static List<GeoPoint> parseAll(List<String> polylines) {
        Objects.requireNonNull(polylines, "polylines");
        List<GeoPoint> merged = new ArrayList<>();
        for (String polyline : polylines) {
            if (polyline == null || polyline.isBlank()) {
                continue;
            }
            for (GeoPoint point : parse(polyline)) {
                if (merged.isEmpty()
                        || CoordinateTransform.distanceMeters(merged.getLast(), point) > 0.0D) {
                    merged.add(point);
                }
            }
        }
        return merged;
    }
}
