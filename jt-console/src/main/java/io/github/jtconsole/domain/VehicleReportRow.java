package io.github.jtconsole.domain;

/** 车辆运营报表的一行：某车在某时间范围内的聚合指标。 */
public record VehicleReportRow(
        String deviceId,
        String plateNo,
        double totalDistanceKm,
        int activeDays,
        int totalAlarms,
        double maxSpeedKph) {
}
