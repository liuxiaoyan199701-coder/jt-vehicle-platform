package io.github.jtconsole.domain;

import java.util.List;

public record VehicleProfile(
        Vehicle vehicle,
        LiveStatus status,
        TodayMetrics today,
        Last7DaysMetrics last7Days,
        long openAlarmCount,
        List<AlarmEvent> recentAlarms) {

    public VehicleProfile {
        recentAlarms = List.copyOf(recentAlarms);
    }

    public record TodayMetrics(
            String date,
            double distanceKm,
            int pointCount,
            int movingPoints,
            double maxSpeedKph,
            int alarmCount) {}

    public record Last7DaysMetrics(
            double distanceKm,
            int activeDays,
            double maxSpeedKph,
            int alarmCount) {}
}
