package io.github.jtconsole.domain;

/** 车辆-司机驾驶区间。当前驾驶员即 ended_at 为空的那段。 */
public record DriverSession(
        Long id,
        String deviceId,
        Long driverId,
        String driverName,
        String licenseNo,
        String startedAt,
        String endedAt,
        String source) {

    public static final String SOURCE_CARD = "CARD";
    public static final String SOURCE_MANUAL = "MANUAL";
}
