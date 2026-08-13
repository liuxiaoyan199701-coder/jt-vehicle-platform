package io.github.jtconsole.operations;

import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.domain.VehicleDailyStat;
import io.github.jtconsole.domain.VehicleProfile;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.DailyStatRepository;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.repository.VehicleRepository;
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

    @Transactional(readOnly = true)
    public Optional<VehicleProfile> find(String deviceId) {
        // 设备未建档也返回详情：状态、轨迹汇总、告警都按 deviceId 聚合，
        // 与车辆档案表无关；vehicle 为 null 由前端做「未建档」降级展示
        Optional<Vehicle> vehicle = vehicles.findById(deviceId);
        LocalDate today = dates.today();
        VehicleDailyStat todayStat = stats.find(deviceId, today.toString())
                .orElse(VehicleDailyStat.empty(deviceId, today.toString()));
        List<VehicleDailyStat> sevenDays = stats.findByDeviceRange(
                deviceId, today.minusDays(6).toString(), today.toString());
        double distance = sevenDays.stream().mapToDouble(VehicleDailyStat::distanceKm).sum();
        int activeDays = (int) sevenDays.stream().filter(value -> value.pointCount() > 0).count();
        double maxSpeed = sevenDays.stream().mapToDouble(VehicleDailyStat::maxSpeedKph).max().orElse(0);
        int alarmCount = sevenDays.stream().mapToInt(VehicleDailyStat::alarmCount).sum();
        return Optional.of(new VehicleProfile(
                vehicle.orElse(null), statuses.findLiveByDevice(deviceId).orElse(null),
                new VehicleProfile.TodayMetrics(today.toString(), round(todayStat.distanceKm()),
                        todayStat.pointCount(), todayStat.movingPoints(),
                        round(todayStat.maxSpeedKph()), todayStat.alarmCount()),
                new VehicleProfile.Last7DaysMetrics(
                        round(distance), activeDays, round(maxSpeed), alarmCount),
                alarms.countOpenByDevice(deviceId), alarms.recentByDevice(deviceId, 10)));
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
