package io.github.jtconsole.domain;

/** 车队详情中的成员车辆及其当前运营指标。 */
public record FleetMember(
        Vehicle vehicle,
        Fleet.Summary fleet,
        boolean online,
        Double speedKph,
        String lastSeenAt,
        long openAlarmCount,
        double todayDistanceKm) {
}
