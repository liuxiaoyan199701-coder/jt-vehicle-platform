package io.github.jtconsole.ai.tool;

import io.github.jtconsole.domain.AlarmEvent;
import io.github.jtconsole.domain.AlarmLevel;
import io.github.jtconsole.domain.AlarmPage;
import io.github.jtconsole.domain.AlarmSource;
import io.github.jtconsole.domain.AlarmStatus;
import io.github.jtconsole.domain.TrackPoint;
import io.github.jtconsole.domain.VehicleDailyStat;
import io.github.jtconsole.geo.ReverseGeocoder;
import io.github.jtconsole.operations.BusinessDateService;
import io.github.jtconsole.operations.TrackSummary;
import io.github.jtconsole.repository.AlarmRepository;
import io.github.jtconsole.repository.DailyStatRepository;
import io.github.jtconsole.repository.TrackRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 告警、轨迹与日统计的查询工具。
 *
 * <p>轨迹是这里最需要克制的一个：一次查询能取回上千个点，但模型答不出「第 437 个点在哪」，
 * 直接喂过去只是烧钱加撑爆上下文。所以它**不返回原始点集**，只给汇总加等距抽样。
 */
@Component
public class OperationsTools {

    private static final int MAX_ALARMS = 30;
    private static final int MAX_TRACK_POINTS = 2000;
    private static final int TRACK_SAMPLE = 40;
    private static final int MAX_DAYS = 31;

    private final ToolRunner runner;
    private final ReverseGeocoder geocoder;
    private final AlarmRepository alarms;
    private final TrackRepository tracks;
    private final DailyStatRepository dailyStats;
    private final BusinessDateService dates;

    public OperationsTools(
            ToolRunner runner,
            ReverseGeocoder geocoder,
            AlarmRepository alarms,
            TrackRepository tracks,
            DailyStatRepository dailyStats,
            BusinessDateService dates) {
        this.runner = runner;
        this.geocoder = geocoder;
        this.alarms = alarms;
        this.tracks = tracks;
        this.dailyStats = dailyStats;
        this.dates = dates;
    }

    @Tool(name = "get_current_time",
            description = "获取平台当前的业务日期与时间。用户说「今天」「昨天」「这周」时，先用它确定"
                    + "具体日期再去查数据，不要凭训练数据猜今天是几号。")
    String currentTime(ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "get_current_time", "确认当前日期", () -> {
            LocalDate today = dates.today();
            return Map.of(
                    "today", today.toString(),
                    "yesterday", today.minusDays(1).toString(),
                    "zone", dates.zoneId().getId(),
                    "now", dates.now().toString());
        });
    }

    @Tool(name = "search_alarms",
            description = "按条件检索告警。可按状态、级别、来源、设备、类型、时间范围筛选。"
                    + "回答「有哪些告警」「某台车报过什么警」「昨天的告警」时用它。"
                    + "只想知道数量请改用 get_alarm_stats，那个便宜得多。最多返回 30 条，总数见 total。")
    String searchAlarms(
            @ToolParam(description = "状态：OPEN 未处理 / ACKNOWLEDGED 已确认 / CLOSED 已关闭",
                    required = false) String status,
            @ToolParam(description = "级别：LOW / MEDIUM / HIGH / CRITICAL", required = false)
            String level,
            @ToolParam(description = "设备号，限定某台车", required = false) String deviceId,
            @ToolParam(description = "告警类型，如 overspeed、fatigueDriving", required = false)
            String type,
            @ToolParam(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd",
                    required = false) String start,
            @ToolParam(description = "结束时间，格式同上", required = false) String end,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "search_alarms", "检索告警", () -> {
            AlarmRepository.AlarmFilter filter = new AlarmRepository.AlarmFilter(
                    parse(AlarmStatus.class, status), parse(AlarmLevel.class, level),
                    parse(AlarmSource.class, null), blankToNull(deviceId), blankToNull(type),
                    null, blankToNull(start), blankToNull(end), 1, MAX_ALARMS);
            AlarmPage page = alarms.search(filter, session.scope());
            List<Map<String, Object>> rows = page.items().stream()
                    .map(OperationsTools::alarmBrief)
                    .toList();
            Map<String, Object> result = ToolResults.page("alarms", rows, MAX_ALARMS, page.total());
            describeAlarmLocations(rows);
            return result;
        });
    }

    @Tool(name = "get_alarm_stats",
            description = "统计未处理告警的总数与各级别数量，并附最近几条。"
                    + "回答「有多少告警」「告警严不严重」时用它，比逐条列出省得多。")
    String alarmStats(ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "get_alarm_stats", "统计告警", () -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("openTotal", alarms.countOpen(session.scope()));
            result.put("criticalOpen", alarms.countCriticalOpen(session.scope()));
            Map<String, Long> byLevel = new LinkedHashMap<>();
            for (AlarmRepository.LevelCount count : alarms.countOpenByLevel(session.scope())) {
                byLevel.put(count.level().name(), count.count());
            }
            result.put("openByLevel", byLevel);
            result.put("recent", alarms.recent(5, session.scope()).stream()
                    .map(OperationsTools::alarmBrief)
                    .toList());
            return result;
        });
    }

    @Tool(name = "query_track",
            description = """
                    查询一台车在某个时间段的行驶轨迹汇总：里程、最高速、平均速、起止时间，
                    并附少量沿途抽样点。回答「跑了多少公里」「开多快」「去过哪」时用它。
                    注意它返回的是抽样点而不是完整轨迹——完整轨迹请让用户去轨迹回放页看。
                    时间跨度不要超过一天；查一周的里程请改用 get_daily_stats。""")
    String queryTrack(
            @ToolParam(description = "设备号") String deviceId,
            @ToolParam(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss") String start,
            @ToolParam(description = "结束时间，格式同上") String end,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "query_track", "查询轨迹 " + deviceId, () -> {
            if (deviceId == null || deviceId.isBlank()) {
                return ToolResults.error("缺少设备号，请先用车辆查询工具确认是哪一台车。");
            }
            List<TrackPoint> points = tracks.findRange(
                    deviceId.trim(), blankToNull(start), blankToNull(end),
                    MAX_TRACK_POINTS, session.scope());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", deviceId.trim());
            result.put("pointCount", points.size());
            // 汇总走与轨迹回放页同一段代码，保证 AI 说的里程和页面上的是同一个数。
            result.putAll(TrackSummary.of(points));
            if (points.isEmpty()) {
                result.put("note", "该时间段内没有轨迹点，可能是设备离线或时间范围有误。");
                return result;
            }
            List<Map<String, Object>> sampled = new ArrayList<>();
            for (TrackPoint point : ToolResults.sample(points, TRACK_SAMPLE)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", point.deviceTime());
                row.put("lat", point.gcjLat());
                row.put("lng", point.gcjLng());
                row.put("speedKph", point.speedKph());
                sampled.add(row);
            }
            // 只给首末两点解析地址：中间点的地址对回答「去过哪」没有增量价值，却要吃配额。
            describeLocations(List.of(sampled.getFirst(), sampled.getLast()));
            result.put("sampledPoints", sampled);
            result.put("note", "sampledPoints 是等距抽样，不是完整轨迹；里程与速度取自全部 "
                    + points.size() + " 个点。");
            return result;
        });
    }

    @Tool(name = "get_daily_stats",
            description = """
                    按自然日查询里程与活跃度。给了设备号就查这台车逐日的里程、点数与最高速；
                    不给设备号则查全部可见车辆的逐日汇总（里程、活跃车辆数、新增告警数）。
                    回答「这周跑了多少」「哪天跑得最多」时用它——比逐日调 query_track 省得多。
                    时间跨度最多 31 天。""")
    String dailyStats(
            @ToolParam(description = "设备号；留空则查全部可见车辆的汇总", required = false)
            String deviceId,
            @ToolParam(description = "开始日期 yyyy-MM-dd") String start,
            @ToolParam(description = "结束日期 yyyy-MM-dd") String end,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "get_daily_stats", "查询日统计", () -> {
            if (start == null || start.isBlank() || end == null || end.isBlank()) {
                return ToolResults.error("缺少日期范围。可先用 get_current_time 确认今天是哪天。");
            }
            if (deviceId != null && !deviceId.isBlank()) {
                List<VehicleDailyStat> stats = dailyStats.findByDeviceRange(
                        deviceId.trim(), start.trim(), end.trim(), session.scope());
                List<Map<String, Object>> rows = stats.stream()
                        .map(OperationsTools::dailyBrief)
                        .toList();
                return ToolResults.page("days", rows, MAX_DAYS, rows.size());
            }
            List<Map<String, Object>> rows =
                    dailyStats.aggregateRange(start.trim(), end.trim(), session.scope()).stream()
                            .map(aggregate -> {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("date", aggregate.date());
                                row.put("distanceKm", aggregate.distanceKm());
                                row.put("activeVehicles", aggregate.activeVehicles());
                                row.put("newAlarms", aggregate.newAlarms());
                                return row;
                            })
                            .toList();
            return ToolResults.page("days", rows, MAX_DAYS, rows.size());
        });
    }

    private void describeAlarmLocations(List<Map<String, Object>> rows) {
        describeLocations(rows);
    }

    /** 就地补 address 字段；查不到就不加，绝不让地址失败连累主体数据。 */
    private void describeLocations(List<Map<String, Object>> rows) {
        if (!geocoder.available() || rows.isEmpty()) {
            return;
        }
        List<double[]> points = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object lat = row.get("lat");
            Object lng = row.get("lng");
            points.add(lat instanceof Number a && lng instanceof Number b
                    ? new double[] {a.doubleValue(), b.doubleValue()}
                    : null);
        }
        List<String> addresses = geocoder.addresses(points);
        for (int i = 0; i < rows.size(); i++) {
            if (addresses.get(i) != null) {
                rows.get(i).put("address", addresses.get(i));
            }
        }
    }

    private static Map<String, Object> alarmBrief(AlarmEvent alarm) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", alarm.id());
        row.put("deviceId", alarm.deviceId());
        row.put("plateNo", alarm.plateNo());
        row.put("type", alarm.type());
        row.put("title", alarm.title());
        row.put("level", alarm.level().name());
        row.put("status", alarm.status().name());
        row.put("occurredAt", alarm.occurredAt());
        row.put("lat", alarm.gcjLat());
        row.put("lng", alarm.gcjLng());
        return row;
    }

    private static Map<String, Object> dailyBrief(VehicleDailyStat stat) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", stat.date());
        row.put("distanceKm", stat.distanceKm());
        row.put("pointCount", stat.pointCount());
        row.put("movingPoints", stat.movingPoints());
        row.put("maxSpeedKph", stat.maxSpeedKph());
        row.put("alarmCount", stat.alarmCount());
        return row;
    }

    /** 模型给的枚举名可能大小写不一或干脆是中文，认不出就当没筛，而不是让整次查询失败。 */
    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
