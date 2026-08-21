package io.github.jtplatform.simulator.ui;

import java.util.Locale;

/** 地图选点坐标，统一使用既有路径规划采用的 GCJ-02。 */
public record MapPoint(double latitude, double longitude) {
    public MapPoint {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("地图坐标超出范围");
        }
    }

    public String latitudeText() {
        return String.format(Locale.ROOT, "%.6f", latitude);
    }

    public String longitudeText() {
        return String.format(Locale.ROOT, "%.6f", longitude);
    }
}
