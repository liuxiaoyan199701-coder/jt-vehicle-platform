package io.github.jtconsole.domain;

/**
 * 设备最新状态，实时监控页的数据源。
 *
 * <p>{@code lat/lng} 是设备上报的 WGS-84 原值，{@code gcjLat/gcjLng} 是供高德地图渲染的
 * GCJ-02 坐标，前端只用后者。
 */
public record LiveStatus(
        String deviceId,
        String plateNo,
        boolean online,
        String lastSeenAt,
        String deviceTime,
        Double lat,
        Double lng,
        Double gcjLat,
        Double gcjLng,
        Double speedKph,
        Integer direction,
        Integer altitude,
        Double mileage,
        Boolean accOn,
        Boolean positioned,
        String alarmJson,
        String statusJson) {
}
