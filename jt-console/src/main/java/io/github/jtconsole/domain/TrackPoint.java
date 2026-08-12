package io.github.jtconsole.domain;

/**
 * 单个轨迹点。前端画线用 {@code gcjLat/gcjLng}。
 */
public record TrackPoint(
        String deviceTime,
        String receivedAt,
        double lat,
        double lng,
        double gcjLat,
        double gcjLng,
        Double speedKph,
        Integer direction,
        Integer altitude,
        Double mileage) {
}
