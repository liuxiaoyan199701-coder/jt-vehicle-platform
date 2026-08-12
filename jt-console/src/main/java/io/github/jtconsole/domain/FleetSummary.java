package io.github.jtconsole.domain;

/** 车队档案及与全局运营看板一致的实时聚合。 */
public record FleetSummary(
        Fleet fleet,
        int totalVehicles,
        int online,
        int moving,
        int idle,
        int offline,
        long openAlarms,
        double todayDistanceKm) {
}
