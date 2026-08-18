package io.github.jtplatform.simulator.trip;

/**
 * 一个地理坐标点。不携带坐标系信息——坐标系由持有它的上下文决定。
 *
 * <p>刻意不做「带坐标系标签的点」：整条链路上只有一次转换（路线构造时把地图返回的加密坐标转成
 * 原始坐标），标签在别处永远是同一个值，只会增加噪音而不增加安全性。
 */
public record GeoPoint(double lat, double lng) {

    public GeoPoint {
        if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
            throw new IllegalArgumentException("coordinates must be finite: " + lat + "," + lng);
        }
    }
}
