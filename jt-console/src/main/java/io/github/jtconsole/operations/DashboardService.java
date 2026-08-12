package io.github.jtconsole.operations;

import io.github.jtconsole.domain.AlarmLevel;
import io.github.jtconsole.domain.DashboardOverview;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.DailyStatRepository;
import io.github.jtconsole.repository.StatusRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

    private final StatusRepository statuses;
    private final AlarmRepository alarms;
    private final DailyStatRepository stats;
    private final BusinessDateService dates;

    public DashboardService(
            StatusRepository statuses, AlarmRepository alarms,
            DailyStatRepository stats, BusinessDateService dates) {
        this.statuses = statuses;
        this.alarms = alarms;
        this.stats = stats;
        this.dates = dates;
    }

    @Transactional(readOnly = true)
    public DashboardOverview overview() {
        LocalDate today = dates.today();
        LocalDate start = today.minusDays(6);
        StatusRepository.FleetSnapshot fleet = statuses.fleetSnapshot();
        DashboardOverview.Summary summary = new DashboardOverview.Summary(
                fleet.fleetVehicles(), fleet.online(), fleet.offline(), fleet.moving(), fleet.idle(),
                fleet.unknownOnline(), alarms.countOpen(), alarms.countCriticalOpen(),
                round(stats.totalDistance(today.toString())));

        Map<String, DailyStatRepository.DailyAggregate> byDate = new HashMap<>();
        stats.aggregateRange(start.toString(), today.toString())
                .forEach(value -> byDate.put(value.date(), value));
        List<DashboardOverview.DailyTrend> trend = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            String date = start.plusDays(offset).toString();
            DailyStatRepository.DailyAggregate value = byDate.get(date);
            trend.add(value == null
                    ? new DashboardOverview.DailyTrend(date, 0, 0, 0)
                    : new DashboardOverview.DailyTrend(date, round(value.distanceKm()),
                            value.activeVehicles(), value.newAlarms()));
        }

        Map<AlarmLevel, Long> levelCounts = new EnumMap<>(AlarmLevel.class);
        alarms.countOpenByLevel().forEach(value -> levelCounts.put(value.level(), value.count()));
        List<DashboardOverview.AlarmLevelCount> levels = new ArrayList<>();
        for (AlarmLevel level : AlarmLevel.values()) {
            levels.add(new DashboardOverview.AlarmLevelCount(level, levelCounts.getOrDefault(level, 0L)));
        }
        return new DashboardOverview(summary, trend, levels, alarms.recent(10));
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
