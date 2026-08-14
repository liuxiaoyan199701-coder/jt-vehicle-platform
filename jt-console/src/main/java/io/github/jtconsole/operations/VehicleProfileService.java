package io.github.jtconsole.operations;

import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.domain.VehicleDailyStat;
import io.github.jtconsole.domain.VehicleProfile;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.DailyStatRepository;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.repository.VehicleRepository;
import io.github.jtconsole.security.DataScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VehicleProfileService {

    private final VehicleRepository vehicles;
    private final StatusRepository statuses;
    private final DailyStatRepository stats;
    private final AlarmRepository alarms;
    private final BusinessDateService dates;

    public VehicleProfileService(
            VehicleRepository vehicles, StatusRepository statuses,
            DailyStatRepository stats, AlarmRepository alarms, BusinessDateService dates) {
        this.vehicles = vehicles;
        this.statuses = statuses;
        this.stats = stats;
        this.alarms = alarms;
        this.dates = dates;
    }

    /**
     * 单车运营详情。
     *
     * <p>未建档设备仍返回详情（{@code vehicle} 为 null，前端做「未建档」降级展示），
     * 但这只对平台管理员成立——租户用户的世界里只有本租户已建档车辆，
     * 因此范围内查不到车辆档案时一律按「不存在」返回，而不是降级展示别人的设备。
     */
    @Transactional(readOnly = true)
    public Optional<VehicleProfile> find(String deviceId, DataScope scope) {
        Optional<Vehicle> vehicle = vehicles.findById(deviceId, scope);
        boolean unregisteredVisible = scope.isPlatform() && scope.tenantId() == null;
        if (vehicle.isEmpty() && !unregisteredVisible) {
            return Optional.empty();
        }

        LocalDate today = dates.today();
        VehicleDailyStat todayStat = stats.find(deviceId, today.toString())
                .orElse(VehicleDailyStat.empty(deviceId, today.toString()));
        List<VehicleDailyStat> sevenDays = stats.findByDeviceRange(
                deviceId, today.minusDays(6).toString(), today.toString(), scope);
        double distance = sevenDays.stream().mapToDouble(VehicleDailyStat::distanceKm).sum();
        int activeDays = (int) sevenDays.stream().filter(value -> value.pointCount() > 0).count();
        double maxSpeed = sevenDays.stream().mapToDouble(VehicleDailyStat::maxSpeedKph).max().orElse(0);
        int alarmCount = sevenDays.stream().mapToInt(VehicleDailyStat::alarmCount).sum();
        return Optional.of(new VehicleProfile(
                vehicle.orElse(null), statuses.findLiveByDevice(deviceId, scope).orElse(null),
                new VehicleProfile.TodayMetrics(today.toString(), round(todayStat.distanceKm()),
                        todayStat.pointCount(), todayStat.movingPoints(),
                        round(todayStat.maxSpeedKph()), todayStat.alarmCount()),
                new VehicleProfile.Last7DaysMetrics(
                        round(distance), activeDays, round(maxSpeed), alarmCount),
                alarms.countOpenByDevice(deviceId, scope),
                alarms.recentByDevice(deviceId, 10, scope)));
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
