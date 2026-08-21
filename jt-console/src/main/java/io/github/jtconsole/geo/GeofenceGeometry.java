package io.github.jtconsole.geo;

import java.util.List;

/**
 * 围栏几何判定：射线法 point-in-polygon 与点到折线的近似球面距离。
 *
 * <p>顶点统一用 {@code double[]{lat, lng}} 表示。车队围栏规模在公里级，折线段距离用
 * 「经纬度差 × 本地每度米数」的局部平面近似，误差在可忽略范围。
 */
public final class GeofenceGeometry {

    private static final double METERS_PER_LAT = 111_320.0D;

    private GeofenceGeometry() {
    }

    /** 射线法判断点是否在多边形内（顶点按顺序，无需显式闭合）。 */
    public static boolean pointInPolygon(double lat, double lng, List<double[]> polygon) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = polygon.get(i)[0];
            double yj = polygon.get(j)[0];
            double xi = polygon.get(i)[1];
            double xj = polygon.get(j)[1];
            boolean crosses = (yi > lat) != (yj > lat);
            if (crosses) {
                double xAtY = (xj - xi) * (lat - yi) / (yj - yi) + xi;
                if (lng < xAtY) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /** 点到折线各线段的最短距离（米）。 */
    public static double distanceToPolylineMeters(double lat, double lng, List<double[]> polyline) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = 1; i < polyline.size(); i++) {
            double[] a = polyline.get(i - 1);
            double[] b = polyline.get(i);
            min = Math.min(min, distanceToSegmentMeters(lat, lng, a[0], a[1], b[0], b[1]));
        }
        return min;
    }

    /** 点到线段的近似球面最短距离（米）。 */
    public static double distanceToSegmentMeters(
            double lat, double lng,
            double lat1, double lng1,
            double lat2, double lng2) {
        double metersPerLng = METERS_PER_LAT * Math.cos(Math.toRadians(lat));
        double x = lng * metersPerLng;
        double y = lat * METERS_PER_LAT;
        double x1 = lng1 * metersPerLng;
        double y1 = lat1 * METERS_PER_LAT;
        double x2 = lng2 * metersPerLng;
        double y2 = lat2 * METERS_PER_LAT;

        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSq = dx * dx + dy * dy;
        double t = lengthSq == 0 ? 0 : ((x - x1) * dx + (y - y1) * dy) / lengthSq;
        t = Math.max(0, Math.min(1, t));
        double cx = x1 + t * dx;
        double cy = y1 + t * dy;
        return Math.hypot(x - cx, y - cy);
    }
}
