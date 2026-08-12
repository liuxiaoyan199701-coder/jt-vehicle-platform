package io.github.jtconsole.domain;

public record VehicleDailyStat(
        String deviceId,
        String date,
        double distanceKm,
        int pointCount,
        int movingPoints,
        double maxSpeedKph,
        int alarmCount,
        Double lastLat,
        Double lastLng,
        Double lastMileage,
        String lastDeviceTime) {

    public static VehicleDailyStat empty(String deviceId, String date) {
        return new VehicleDailyStat(deviceId, date, 0, 0, 0, 0, 0, null, null, null, null);
    }
}
