package io.github.jtconsole.domain;

import java.util.List;

public record DashboardOverview(
        Summary summary,
        List<DailyTrend> dailyTrend,
        List<AlarmLevelCount> alarmLevels,
        List<AlarmEvent> recentAlarms) {

    public DashboardOverview {
        dailyTrend = List.copyOf(dailyTrend);
        alarmLevels = List.copyOf(alarmLevels);
        recentAlarms = List.copyOf(recentAlarms);
    }

    public record Summary(
            int fleetVehicles,
            int online,
            int offline,
            int moving,
            int idle,
            int unknownOnline,
            long openAlarms,
            long criticalOpenAlarms,
            double todayDistanceKm) {}

    public record DailyTrend(String date, double distanceKm, int activeVehicles, int newAlarms) {}

    public record AlarmLevelCount(AlarmLevel level, long count) {}
}
