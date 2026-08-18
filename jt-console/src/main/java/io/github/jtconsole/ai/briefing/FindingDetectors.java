package io.github.jtconsole.ai.briefing;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.LiveStatus;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.DailyStatRepository;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.security.DataScope;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 候选发现的检测器：**全部是普通代码，不经模型**。
 *
 * <p>每个检测器回答一个具体问题（哪些车离线太久、告警是不是比昨天多得多），
 * 阈值写死在这里并注明依据。模型看不到这些代码，也无从改变结论——它只能从产出的清单里挑。
 *
 * <p><b>没发现就不产出</b>。一块每天都报「一切正常」的看板，人两周后就不看了；
 * 而真出事那天的那一条，会淹没在同样的措辞里。空清单是合法且常见的结果。
 */
@Component
public class FindingDetectors {

    /**
     * 离线多久算「值得说」。
     *
     * <p>取 6 小时的依据：设备夜间停运熄火是常态，短时离线没有信息量；而营运车辆连续 6 小时
     * 没有任何上报，通常意味着终端掉线、欠费停机或设备故障，是要人去管的事。
     */
    private static final Duration OFFLINE_THRESHOLD = Duration.ofHours(6);

    /** 告警同比涨幅超过这个比例才提。50% 以下的波动在小车队里是噪声。 */
    private static final double ALARM_SURGE_RATIO = 0.5;

    /** 告警基数太小时不谈比例：从 2 条涨到 4 条是「涨了 100%」，但那不是信息。 */
    private static final int ALARM_SURGE_FLOOR = 5;

    /** 一次最多产出多少条候选。模型侧还会再筛，这里是防止清单本身失控。 */
    private static final int MAX_FINDINGS = 24;

    private final StatusRepository statuses;
    private final AlarmRepository alarms;
    private final DailyStatRepository dailyStats;
    private final BusinessDateService dates;

    public FindingDetectors(
            StatusRepository statuses,
            AlarmRepository alarms,
            DailyStatRepository dailyStats,
            BusinessDateService dates) {
        this.statuses = statuses;
        this.alarms = alarms;
        this.dailyStats = dailyStats;
        this.dates = dates;
    }

    /**
     * 跑一遍全部检测器。
     *
     * @param scope 数据范围。简报按租户生成，这里传的是整租户的范围
     */
    public List<DashboardFinding> detect(DataScope scope) {
        List<DashboardFinding> findings = new ArrayList<>();
        findings.addAll(offlineVehicles(scope));
        findings.addAll(alarmSurge(scope));
        findings.addAll(fleetSnapshot(scope));
        findings.addAll(idleVehicles(scope));
        return findings.size() > MAX_FINDINGS ? findings.subList(0, MAX_FINDINGS) : findings;
    }

    /**
     * 离线超过阈值的车。
     *
     * <p>按离线时长倒序，只取最久的几台——「37 台车离线」这种清单对运营没有用，
     * 但「最久的那台已经 3 天没上报」是。
     */
    private List<DashboardFinding> offlineVehicles(DataScope scope) {
        OffsetDateTime now = OffsetDateTime.now(dates.zoneId());
        List<Offline> offline = new ArrayList<>();
        for (LiveStatus status : statuses.findAllLive(scope)) {
            if (status.online()) {
                continue;
            }
            Duration silent = silenceOf(status, now);
            if (silent != null && silent.compareTo(OFFLINE_THRESHOLD) >= 0) {
                offline.add(new Offline(status, silent));
            }
        }
        if (offline.isEmpty()) {
            return List.of();
        }
        offline.sort(Comparator.comparing((Offline o) -> o.silent).reversed());

        List<DashboardFinding> findings = new ArrayList<>();
        int index = 0;
        for (Offline item : offline.subList(0, Math.min(3, offline.size()))) {
            index++;
            long hours = item.silent.toHours();
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("车牌", label(item.status));
            facts.put("设备号", item.status.deviceId());
            facts.put("离线时长(小时)", hours);
            facts.put("最后上报", item.status.lastSeenAt());
            findings.add(new DashboardFinding(
                    "offline-" + index,
                    DashboardFinding.Category.OFFLINE,
                    hours >= 24 ? DashboardFinding.Severity.CRITICAL : DashboardFinding.Severity.WARN,
                    "%s 已连续 %d 小时没有上报".formatted(label(item.status), hours),
                    facts,
                    List.of(item.status.deviceId()),
                    new DashboardFinding.Link(
                            "track", Map.of("device", item.status.deviceId()), "查看轨迹")));
        }
        if (offline.size() > 3) {
            findings.add(new DashboardFinding(
                    "offline-more",
                    DashboardFinding.Category.OFFLINE,
                    DashboardFinding.Severity.INFO,
                    "另有 %d 台车离线超过 %d 小时".formatted(
                            offline.size() - 3, OFFLINE_THRESHOLD.toHours()),
                    Map.of("台数", offline.size() - 3),
                    // 归并条目涉及多台车，逐一列出才能在读取时正确过滤。
                    offline.subList(3, offline.size()).stream().map(o -> o.status.deviceId()).toList(),
                    new DashboardFinding.Link("monitor", Map.of(), "去监控页")));
        }
        return findings;
    }

    /** 今日告警与昨日对比。 */
    private List<DashboardFinding> alarmSurge(DataScope scope) {
        LocalDate today = dates.today();
        List<DailyStatRepository.DailyAggregate> range =
                dailyStats.aggregateRange(today.minusDays(1).toString(), today.toString(), scope);
        if (range.size() < 2) {
            return List.of();
        }
        Map<String, Integer> byDate = new LinkedHashMap<>();
        range.forEach(row -> byDate.put(row.date(), row.newAlarms()));
        Integer yesterday = byDate.get(today.minusDays(1).toString());
        Integer todayCount = byDate.get(today.toString());
        if (yesterday == null || todayCount == null || todayCount < ALARM_SURGE_FLOOR) {
            return List.of();
        }
        if (yesterday == 0 || todayCount <= yesterday * (1 + ALARM_SURGE_RATIO)) {
            return List.of();
        }
        int growth = (int) Math.round((todayCount - yesterday) * 100.0 / yesterday);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("今日新增告警", todayCount);
        facts.put("昨日新增告警", yesterday);
        facts.put("涨幅(%)", growth);
        return List.of(new DashboardFinding(
                "alarm-surge",
                DashboardFinding.Category.ALARM,
                growth >= 200 ? DashboardFinding.Severity.CRITICAL : DashboardFinding.Severity.WARN,
                "今日新增告警 %d 条，较昨日上涨 %d%%".formatted(todayCount, growth),
                facts,
                // 聚合结论：不涉及具体车辆，数据范围不全时整条不给。
                List.of(),
                new DashboardFinding.Link("alarm", Map.of(), "去告警中心")));
    }

    /** 车队总体：在线率与未处置告警。 */
    private List<DashboardFinding> fleetSnapshot(DataScope scope) {
        StatusRepository.FleetSnapshot snapshot = statuses.fleetSnapshot(scope);
        int total = snapshot.online() + snapshot.offline();
        if (total == 0) {
            return List.of();
        }
        List<DashboardFinding> findings = new ArrayList<>();
        long critical = alarms.countCriticalOpen(scope);
        if (critical > 0) {
            findings.add(new DashboardFinding(
                    "alarm-critical-open",
                    DashboardFinding.Category.ALARM,
                    DashboardFinding.Severity.CRITICAL,
                    "有 %d 条严重告警尚未处置".formatted(critical),
                    Map.of("未处置严重告警", critical),
                    List.of(),
                    new DashboardFinding.Link(
                            "alarm", Map.of("level", "CRITICAL", "status", "OPEN"), "去处置")));
        }
        int onlineRate = (int) Math.round(snapshot.online() * 100.0 / total);
        // 在线率只在明显偏低时才提。天天报「在线率 96%」是噪声。
        if (onlineRate < 70) {
            findings.add(new DashboardFinding(
                    "fleet-online-rate",
                    DashboardFinding.Category.FLEET,
                    onlineRate < 50 ? DashboardFinding.Severity.WARN : DashboardFinding.Severity.INFO,
                    "当前在线率 %d%%（%d/%d）".formatted(onlineRate, snapshot.online(), total),
                    Map.of("在线", snapshot.online(), "离线", snapshot.offline(), "在线率(%)", onlineRate),
                    List.of(),
                    new DashboardFinding.Link("monitor", Map.of(), "去监控页")));
        }
        return findings;
    }

    /** 在线但一整天没跑的车。 */
    private List<DashboardFinding> idleVehicles(DataScope scope) {
        double distance = dailyStats.totalDistance(dates.today().toString(), scope);
        StatusRepository.FleetSnapshot snapshot = statuses.fleetSnapshot(scope);
        if (snapshot.online() == 0) {
            return List.of();
        }
        // 有车在线却整体零里程，通常是位置没入库或设备只上报心跳，值得看一眼。
        if (distance > 0.5) {
            return List.of();
        }
        return List.of(new DashboardFinding(
                "fleet-zero-distance",
                DashboardFinding.Category.MILEAGE,
                DashboardFinding.Severity.WARN,
                "今日有 %d 台车在线，但全队里程接近 0".formatted(snapshot.online()),
                Map.of("在线车辆", snapshot.online(), "今日里程(km)", Math.round(distance * 10) / 10.0),
                List.of(),
                new DashboardFinding.Link("monitor", Map.of(), "去监控页")));
    }

    private static String label(LiveStatus status) {
        return status.plateNo() == null || status.plateNo().isBlank()
                ? status.deviceId() : status.plateNo();
    }

    /**
     * 距最后一次上报过了多久。
     *
     * <p>解析失败返回 null 而不是当成「离线很久」：一个解析不了的时间戳是数据问题，
     * 把它报成「该车离线三年」只会让人去查一台其实没事的车。
     */
    private static Duration silenceOf(LiveStatus status, OffsetDateTime now) {
        String lastSeen = status.lastSeenAt();
        if (lastSeen == null || lastSeen.isBlank()) {
            return null;
        }
        // 库里的时间列已统一为 +08:00（见 V6/V8 迁移），取本地部分再挂上同一偏移即可。
        return Timestamps.toLocalDateTime(lastSeen)
                .map(seen -> Duration.between(seen.atOffset(Timestamps.ZONE), now))
                .filter(duration -> !duration.isNegative())
                .orElse(null);
    }

    private record Offline(LiveStatus status, Duration silent) {}
}
