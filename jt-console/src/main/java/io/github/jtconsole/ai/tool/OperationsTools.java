package io.github.jtconsole.ai.tool;

import io.github.jtconsole.domain.AlarmEvent;
import io.github.jtconsole.domain.AlarmLevel;
import io.github.jtconsole.domain.AlarmPage;
import io.github.jtconsole.domain.AlarmSource;
import io.github.jtconsole.domain.AlarmStatus;
import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.domain.TrackPoint;
import io.github.jtconsole.domain.VehicleDailyStat;
import io.github.jtconsole.ai.agent.AiEvent;
import io.github.jtconsole.ai.view.ChartSpec;
import io.github.jtconsole.ai.view.ViewProposalService;
import io.github.jtconsole.ai.vision.SnapshotVisionService;
import io.github.jtconsole.repository.MediaRepository;
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
    /**
     * 一次抓拍查询最多返回几条元数据。
     *
     * <p>比识别上限（默认 4 张）大得多是有意的：元数据便宜，「这周拍了 18 张」本身就是答案；
     * 真正贵的是看图，那一步另有自己的上限。
     */
    private static final int PHOTO_QUERY_LIMIT = 20;

    private final ToolRunner runner;
    private final ReverseGeocoder geocoder;
    private final AlarmRepository alarms;
    private final TrackRepository tracks;
    private final DailyStatRepository dailyStats;
    private final BusinessDateService dates;
    private final ViewProposalService views;
    private final MediaRepository media;
    private final SnapshotVisionService snapshotVision;

    public OperationsTools(
            ToolRunner runner,
            ReverseGeocoder geocoder,
            AlarmRepository alarms,
            TrackRepository tracks,
            DailyStatRepository dailyStats,
            BusinessDateService dates,
            ViewProposalService views,
            MediaRepository media,
            SnapshotVisionService snapshotVision) {
        this.runner = runner;
        this.geocoder = geocoder;
        this.alarms = alarms;
        this.tracks = tracks;
        this.dailyStats = dailyStats;
        this.dates = dates;
        this.views = views;
        this.media = media;
        this.snapshotVision = snapshotVision;
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
                    时间跨度不要超过一天；查一周的里程请改用 get_daily_stats。
                    用户想「看看」轨迹、问「走的什么路线」时把 showMap 设为 true，平台会同时画出轨迹地图。""")
    String queryTrack(
            @ToolParam(description = "设备号") String deviceId,
            @ToolParam(description = "开始时间，格式 yyyy-MM-ddTHH:mm:ss，如 2026-08-17T00:00:00")
            String start,
            @ToolParam(description = "结束时间，格式同上") String end,
            @ToolParam(description = "为 true 时同时在对话里展示轨迹地图", required = false)
            Boolean showMap,
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
            // 轨迹图保持引用型：事件里只带设备号与时间窗，前端自己去取完整轨迹——
            // 那边能拿到两万个点，比这里的 40 个抽样点有用得多。
            maybeTrackMap(session, showMap, deviceId.trim(), start, end);
            return result;
        });
    }

    @Tool(name = "get_daily_stats",
            description = """
                    按自然日查询里程与活跃度。给了设备号就查这台车逐日的里程、点数与最高速；
                    不给设备号则查全部可见车辆的逐日汇总（里程、活跃车辆数、新增告警数）。
                    回答「这周跑了多少」「哪天跑得最多」时用它——比逐日调 query_track 省得多。
                    时间跨度最多 31 天。
                    用户问「趋势」「哪天最多」「这周怎么样」时把 showChart 设为 true，平台会同时画一张趋势图；
                    只问一个总数时不要设。""")
    String dailyStats(
            @ToolParam(description = "设备号；留空则查全部可见车辆的汇总", required = false)
            String deviceId,
            @ToolParam(description = "开始日期 yyyy-MM-dd") String start,
            @ToolParam(description = "结束日期 yyyy-MM-dd") String end,
            @ToolParam(description = "为 true 时同时在对话里展示一张趋势图", required = false)
            Boolean showChart,
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
                maybeChart(session, showChart, rows, "里程", "km", "distanceKm",
                        "get_daily_stats " + deviceId.trim() + " " + start.trim() + "~" + end.trim(),
                        deviceId.trim() + " 逐日里程");
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
            maybeChart(session, showChart, rows, "里程", "km", "distanceKm",
                    "get_daily_stats 全部车辆 " + start.trim() + "~" + end.trim(),
                    "全部车辆逐日里程");
            return ToolResults.page("days", rows, MAX_DAYS, rows.size());
        });
    }

    /** 触发一张轨迹图。校验（含跨度上限）由提议服务负责，这里只管把参数递过去。 */
    @Tool(name = "query_photos",
            description = """
                    查询一台车已经拍到的抓拍照片，**并直接告诉你照片里是什么**。
                    回答「拍到了什么」「车上什么情况」「有没有拍到人/货物/事故」时用它。
                    你会拿到照片的时间、通道、位置，以及前几张的画面内容描述——
                    描述里没提到的东西就是看不出来，不要替它补充。
                    时间跨度最多 7 天；不给时间就是查最近的。

                    这个工具查的是**已经拍好的照片**；要让车现在去拍一张，用 propose_action 的
                    take_photo（通道参数叫 channel，与本工具一致）。

                    **只要用户可能想看照片，就把 showGallery 设为 true**——问「拍到了什么」
                    「看一下在干什么」「有没有拍到人」都算。抓拍指令执行完的那一轮尤其要设，
                    用户等的就是那张图，只用文字描述等于没给。
                    照片墙的标题会自动带上最新一张的拍摄时间，不会和「待确认的新抓拍」混淆。""")
    String queryPhotos(
            @ToolParam(description = "设备号") String deviceId,
            @ToolParam(description = "开始时间，格式 yyyy-MM-ddTHH:mm:ss；留空表示最近", required = false)
            String start,
            @ToolParam(description = "结束时间，格式同上；留空表示最近", required = false) String end,
            @ToolParam(
                    description = "是否在对话里展示照片，默认 true。只有用户明确只要数量、不想看图时才设 false",
                    required = false)
            Boolean showGallery,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "query_photos", "查询抓拍 " + deviceId, () -> {
            if (deviceId == null || deviceId.isBlank()) {
                return ToolResults.error("缺少设备号，请先用车辆查询工具确认是哪一台车。");
            }
            String device = deviceId.trim();
            MediaRepository.MediaFilter filter = new MediaRepository.MediaFilter(
                    device, null, null, null, null,
                    blankToNull(start), blankToNull(end), 1, PHOTO_QUERY_LIMIT);
            List<MediaFile> photos = media.search(filter, session.scope()).items();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", device);
            result.put("photoCount", photos.size());
            if (photos.isEmpty()) {
                result.put("note", "这段时间没有抓拍照片。可能是没人下发过拍照指令，也可能设备当时离线。");
                return result;
            }

            // 画面描述折在这里，不另起一个工具：界面上不该出现「第二个模型」，
            // 而且模型经常忘记去调那种需要额外一轮的辅助工具。
            SnapshotVisionService.Described described = snapshotVision.describe(photos);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (MediaFile photo : photos) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", photo.capturedAt());
                row.put("channel", photo.channelId());
                row.put("trigger", photo.alarmTriggered() ? "报警触发" : "指令或定时");
                if (photo.locatable()) {
                    row.put("lat", photo.gcjLat());
                    row.put("lng", photo.gcjLng());
                }
                rows.add(row);
            }
            // 只给带位置的首张解析地址，理由同轨迹：地址要吃逆地理配额。
            rows.stream().filter(row -> row.containsKey("lat")).findFirst()
                    .ifPresent(row -> describeLocations(List.of(row)));
            result.put("photos", rows);

            // 描述放结果顶层而不是逐张挂：模型的分段措辞每次都可能不同，按「第 N 张」拆开
            // 容易错位，把一张照片的画面安到另一张头上，比不拆分严重得多。
            if (!described.isEmpty()) {
                result.put("画面内容", described.text());
                result.put("画面内容覆盖的拍摄时间", described.coveredTimes());
                if (described.coveredTimes().size() < photos.size()) {
                    result.put("note", "只识别了上列时间对应的照片，其余仅有元数据，"
                            + "不要描述它们的画面内容。");
                }
            } else {
                result.put("note", snapshotVision.available()
                        // 如实说明，模型才不会对着元数据编出画面内容。
                        ? "照片画面这次没能读取，只能提供拍摄时间与位置，不要描述画面内容。"
                        : "平台未启用图片识别，无法得知画面内容，不要猜测照片里有什么。");
            }
            // 标题带上最新一张的时间：万一模型在「即将提议新抓拍」时仍然出了图，
            // 用户至少能一眼看出这些是**已有的**照片，而不是刚拍的那张。
            maybePhotoGallery(session, showGallery, device, start, end,
                    photos.getFirst().capturedAt());
            return result;
        });
    }

    /**
     * 抓拍照片墙。
     *
     * <p><b>默认展示，与其它视图相反。</b>其它视图（轨迹、图表）是「加分项」，模型不出图也答得完整；
     * 而照片的价值几乎全在画面上，只给一段文字描述等于没给。线上实测过：把展示与否完全交给模型
     * 判断，它在「抓拍执行完」这个最该出图的时刻反而没出，用户问「怎么没看到图片」。
     *
     * <p>模型仍可显式传 {@code false} 关闭（比如用户只问「一共几张」）。
     * 同一台车一轮只出一张由 {@code ViewBudget} 的签名去重保证，不会刷屏。
     */
    private void maybePhotoGallery(
            ToolSession session, Boolean showGallery, String deviceId,
            String start, String end, String latestAt) {
        if (Boolean.FALSE.equals(showGallery)) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("deviceId", deviceId);
        params.put("start", blankToNull(start));
        params.put("end", blankToNull(end));
        // 「已有抓拍」而不是「抓拍照片」：后者在紧跟着一张待确认抓拍卡时会被读成
        // 「刚拍的那张」。标题里再带上最新一张的时间，歧义就没有了。
        String title = "%s 已有抓拍（最新 %s）".formatted(deviceId, shortTime(latestAt));
        ViewProposalService.Outcome outcome =
                views.propose(session, "photo_gallery", title, params);
        if (outcome.accepted()) {
            session.events().emit(
                    new AiEvent(AiEvent.Kind.VIEW, outcome.proposal().asEventData()));
        }
    }

    /** 只取到分钟，标题里不需要秒与时区后缀。 */
    private static String shortTime(String timestamp) {
        if (timestamp == null || timestamp.length() < 16) {
            return timestamp == null ? "" : timestamp;
        }
        return timestamp.substring(5, 16).replace('T', ' ');
    }

    private void maybeTrackMap(
            ToolSession session, Boolean showMap, String deviceId, String start, String end) {
        if (!Boolean.TRUE.equals(showMap)) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("deviceId", deviceId);
        params.put("start", start == null ? null : start.trim());
        params.put("end", end == null ? null : end.trim());
        ViewProposalService.Outcome outcome =
                views.propose(session, "track_map", deviceId + " 行驶轨迹", params);
        if (outcome.accepted()) {
            session.events().emit(
                    new AiEvent(AiEvent.Kind.VIEW, outcome.proposal().asEventData()));
        }
    }

    /**
     * 用服务端**自己刚查到的行**组装图表。
     *
     * <p>这是图表的默认路径，也是准确的那条：让模型把这 7~31 行数字再抄一遍进 show_chart 的参数，
     * 等于把一份准确数据经过一次幻觉信道再拿回来，还多烧一遍输出 token（输出通常比输入贵）。
     * 决定点因此从「查完后想起来再调一次」前移到「查的时候一起说」——顺带消除了
     * 「模型忘记调第二个工具」这个失败模式。
     *
     * <p>出图失败不影响查询结果：图是附加价值，不该因为它没画成就让用户连数字都拿不到。
     */
    private void maybeChart(
            ToolSession session, Boolean showChart, List<Map<String, Object>> rows,
            String seriesName, String unit, String valueKey, String source, String title) {
        if (!Boolean.TRUE.equals(showChart) || rows.isEmpty()) {
            return;
        }
        List<String> categories = rows.stream()
                .map(row -> String.valueOf(row.get("date")))
                .toList();
        List<Double> data = rows.stream()
                .map(row -> row.get(valueKey))
                // 缺测保持为空——那天没上报是「断线」，填 0 会被读成「跑了 0 公里」。
                .map(value -> value instanceof Number number ? number.doubleValue() : null)
                .toList();
        ChartSpec spec = new ChartSpec("line", title, source, categories,
                List.of(new ChartSpec.Series(seriesName, null, unit, data)), false);
        ViewProposalService.Outcome outcome = views.propose(session, spec);
        if (outcome.accepted()) {
            session.events().emit(
                    new AiEvent(AiEvent.Kind.VIEW, outcome.proposal().asEventData()));
        }
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
