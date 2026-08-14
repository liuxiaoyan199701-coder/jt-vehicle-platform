package io.github.jtconsole.operations;

import io.github.jtconsole.domain.VehicleDailyStat;
import io.github.jtconsole.geo.CoordTransform;
import io.github.jtconsole.repository.DailyStatRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        recordAll(deviceId, List.of(new Point(deviceTime, receivedAt, lat, lng, speedKph, mileage)));
    }

    /**
     * 一次记录多个点，按自然日聚合后每天只做一次读-改-写。
     *
     * <p>批量补传一次可能带来上千个点。逐点读-改-写会在同一个事务里对同一行做上千次
     * SELECT + UPSERT，而 SQLite 只有一把写锁——那会把整个投递通道卡住。
     *
     * <p>点应当已按设备时间升序排列：里程累加依赖严格递增的设备时间，乱序点只计数不计里程。
     */
    public void recordAll(String deviceId, List<Point> points) {
        if (points.isEmpty()) {
            return;
        }
        Map<LocalDate, List<Point>> byDate = new LinkedHashMap<>();
        for (Point point : points) {
            byDate.computeIfAbsent(dates.resolve(point.deviceTime(), point.receivedAt()),
                    ignored -> new ArrayList<>()).add(point);
        }
        String updatedAt = dates.now().toString();
        byDate.forEach((date, dayPoints) -> {
            VehicleDailyStat stat = stats.find(deviceId, date.toString())
                    .orElse(VehicleDailyStat.empty(deviceId, date.toString()));
            for (Point point : dayPoints) {
                stat = fold(stat, point);
            }
            stats.save(stat, updatedAt);
        });
    }

    /**
     * 把一个点折进当日统计。纯函数，不碰数据库，因此单点与批量可以共用同一套累加规则。
     */
    private static VehicleDailyStat fold(VehicleDailyStat previous, Point point) {
        Optional<LocalDateTime> incomingTime = parseDeviceTime(point.deviceTime());
        Optional<LocalDateTime> previousTime = parseDeviceTime(previous.lastDeviceTime());
        boolean ordered = incomingTime.isPresent()
                && (previousTime.isEmpty() || incomingTime.get().isAfter(previousTime.get()));
        double distance = previous.distanceKm();
        Double lastLat = previous.lastLat();
        Double lastLng = previous.lastLng();
        Double lastMileage = previous.lastMileage();
        String lastDeviceTime = previous.lastDeviceTime();
        if (ordered) {
            distance += distanceIncrement(previous, point.lat(), point.lng(), point.mileage());
            lastLat = point.lat();
            lastLng = point.lng();
            lastMileage = point.mileage();
            lastDeviceTime = point.deviceTime();
        }
        Double speedKph = point.speedKph();
        return new VehicleDailyStat(
                previous.deviceId(), previous.date(), round(distance), previous.pointCount() + 1,
                previous.movingPoints() + (speedKph != null && speedKph > MOVING_SPEED_KPH ? 1 : 0),
                Math.max(previous.maxSpeedKph(), speedKph == null ? 0 : speedKph),
                previous.alarmCount(), lastLat, lastLng, lastMileage, lastDeviceTime);
    }

    /** 参与日统计累加的一个位置点。 */
    public record Point(
            String deviceTime, String receivedAt,
            double lat, double lng, Double speedKph, Double mileage) {
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
