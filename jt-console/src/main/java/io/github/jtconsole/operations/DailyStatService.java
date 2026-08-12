package io.github.jtconsole.operations;

import io.github.jtconsole.domain.VehicleDailyStat;
import io.github.jtconsole.geo.CoordTransform;
import io.github.jtconsole.repository.DailyStatRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DailyStatService {

    private static final double MOVING_SPEED_KPH = 5.0D;
    private static final double MAX_POINT_DISTANCE_KM = 20.0D;
    private static final double MAX_MILEAGE_DELTA_KM = 20.0D;

    private final DailyStatRepository stats;
    private final BusinessDateService dates;

    public DailyStatService(DailyStatRepository stats, BusinessDateService dates) {
        this.stats = stats;
        this.dates = dates;
    }

    public void record(
            String deviceId, String deviceTime, String receivedAt,
            double lat, double lng, Double speedKph, Double mileage) {
        LocalDate date = dates.resolve(deviceTime, receivedAt);
        VehicleDailyStat previous = stats.find(deviceId, date.toString())
                .orElse(VehicleDailyStat.empty(deviceId, date.toString()));
        Optional<LocalDateTime> incomingTime = parseDeviceTime(deviceTime);
        Optional<LocalDateTime> previousTime = parseDeviceTime(previous.lastDeviceTime());
        boolean ordered = incomingTime.isPresent()
                && (previousTime.isEmpty() || incomingTime.get().isAfter(previousTime.get()));
        double distance = previous.distanceKm();
        Double lastLat = previous.lastLat();
        Double lastLng = previous.lastLng();
        Double lastMileage = previous.lastMileage();
        String lastDeviceTime = previous.lastDeviceTime();
        if (ordered) {
            distance += distanceIncrement(previous, lat, lng, mileage);
            lastLat = lat;
            lastLng = lng;
            lastMileage = mileage;
            lastDeviceTime = deviceTime;
        }
        VehicleDailyStat next = new VehicleDailyStat(
                deviceId, date.toString(), round(distance), previous.pointCount() + 1,
                previous.movingPoints() + (speedKph != null && speedKph > MOVING_SPEED_KPH ? 1 : 0),
                Math.max(previous.maxSpeedKph(), speedKph == null ? 0 : speedKph),
                previous.alarmCount(), lastLat, lastLng, lastMileage, lastDeviceTime);
        stats.save(next, dates.now().toString());
    }

    private static double distanceIncrement(
            VehicleDailyStat previous, double lat, double lng, Double mileage) {
        if (previous.lastMileage() != null && mileage != null) {
            double delta = mileage - previous.lastMileage();
            if (delta >= 0 && delta <= MAX_MILEAGE_DELTA_KM) return delta;
        }
        if (previous.lastLat() == null || previous.lastLng() == null) return 0;
        double distance = CoordTransform.distanceMeters(
                previous.lastLat(), previous.lastLng(), lat, lng) / 1000.0D;
        return distance <= MAX_POINT_DISTANCE_KM ? distance : 0;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    private static Optional<LocalDateTime> parseDeviceTime(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(LocalDateTime.parse(value.trim()));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }
}
