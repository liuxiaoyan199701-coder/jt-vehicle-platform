package io.github.jtconsole.ai.tool;

import io.github.jtconsole.domain.FleetSummary;
import io.github.jtconsole.domain.Geofence;
import io.github.jtconsole.domain.LiveStatus;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.geo.ReverseGeocoder;
import io.github.jtconsole.operations.DashboardService;
import io.github.jtconsole.operations.FleetService;
import io.github.jtconsole.operations.GeofenceService;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.StatusRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 车辆、车队、围栏与实时状态的查询工具。
 *
 * <p>全部只读，且每个方法都从 {@link ToolSession} 取数据范围后透传给既有服务——模型无从影响
 * 能看到哪些数据。
 *
 * <p>工具描述刻意写清「什么时候用」而不只是「是什么」：模型选错工具，多半是因为描述只讲了功能。
 */
@Component
public class FleetTools {

    private static final int MAX_LIST = 50;

    private final ToolRunner runner;
    private final ReverseGeocoder geocoder;
    private final DashboardService dashboard;
    private final StatusRepository statuses;
    private final VehicleService vehicles;
    private final FleetService fleets;
    private final GeofenceService geofences;

    public FleetTools(
            ToolRunner runner,
            ReverseGeocoder geocoder,
            DashboardService dashboard,
            StatusRepository statuses,
            VehicleService vehicles,
            FleetService fleets,
            GeofenceService geofences) {
        this.runner = runner;
        this.geocoder = geocoder;
        this.dashboard = dashboard;
        this.statuses = statuses;
        this.vehicles = vehicles;
        this.fleets = fleets;
        this.geofences = geofences;
    }

    @Tool(name = "get_dashboard_overview",
            description = "获取运营看板总览：车辆总数、在线离线数、近七日里程趋势、告警分级统计与最近告警。"
                    + "回答「整体情况怎么样」「今天运营如何」这类概览问题时优先用它，一次调用就能拿到全貌。")
    String dashboardOverview(ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "get_dashboard_overview", "查询运营看板",
                () -> dashboard.overview(session.scope()));
    }

    @Tool(name = "get_fleet_snapshot",
            description = "获取车队实时快照：在线数、离线数、行驶中、怠速、未建档在线设备数。"
                    + "只需要数量而不需要具体是哪些车时用它，比逐台列出省得多。")
    String fleetSnapshot(ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "get_fleet_snapshot", "查询车队快照",
                () -> statuses.fleetSnapshot(session.scope()));
    }

    @Tool(name = "list_live_status",
            description = "列出车辆当前的位置、速度与在线状态。需要知道「具体哪些车」处于某种状态时用它；"
                    + "只想知道数量请改用 get_fleet_snapshot。最多返回 50 台，总数见 total 字段。")
    String liveStatus(
            @ToolParam(description = "为 true 时只返回在线车辆", required = false) Boolean onlineOnly,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        String brief = Boolean.TRUE.equals(onlineOnly) ? "查询在线车辆" : "查询车辆实时状态";
        return runner.run(session, "list_live_status", brief, () -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LiveStatus status : statuses.findAllLive(session.scope())) {
                if (Boolean.TRUE.equals(onlineOnly) && !status.online()) {
                    continue;
                }
                rows.add(brief(status));
            }
            Map<String, Object> result = ToolResults.page("vehicles", rows, MAX_LIST, rows.size());
            // 只给真正返回的那几十条补地址：截断掉的行没人看，不该消耗高德配额。
            describeLocations(asRows(result.get("vehicles")));
            return result;
        });
    }

    @Tool(name = "get_vehicle_status",
            description = "查询单台车辆的档案与当前状态。已经知道设备号或车牌时用它，"
                    + "比列出全部车辆再自行筛选准确得多。")
    String vehicleStatus(
            @ToolParam(description = "设备号（终端 SIM 号）或车牌号") String vehicle,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "get_vehicle_status", "查询车辆 " + vehicle, () -> {
            Optional<Vehicle> found = resolve(vehicle, session);
            if (found.isEmpty()) {
                return ToolResults.error("在你的可见范围内未找到车辆：" + vehicle);
            }
            Vehicle profile = found.get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("profile", profileBrief(profile));
            statuses.findLiveByDevice(profile.deviceId(), session.scope())
                    .ifPresent(status -> {
                        Map<String, Object> row = brief(status);
                        describeLocations(List.of(row));
                        result.put("status", row);
                    });
            return result;
        });
    }

    @Tool(name = "search_vehicles",
            description = "按车牌号或设备号模糊检索车辆档案。用户只说了部分车牌，或想知道有哪些车时用它。")
    String searchVehicles(
            @ToolParam(description = "车牌或设备号的一部分；留空返回全部", required = false) String keyword,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        String label = keyword == null || keyword.isBlank() ? "检索车辆" : "检索车辆：" + keyword;
        return runner.run(session, "search_vehicles", label, () -> {
            String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            List<Map<String, Object>> rows = vehicles.list(session.scope()).stream()
                    .filter(v -> needle.isEmpty() || matches(v, needle))
                    .map(FleetTools::profileBrief)
                    .toList();
            return ToolResults.page("vehicles", rows, MAX_LIST, rows.size());
        });
    }

    @Tool(name = "list_fleets",
            description = "列出车队及其车辆数、在线数、今日里程与未处理告警数。"
                    + "回答「有哪些车队」「某车队跑得怎么样」时用它。")
    String listFleets(
            @ToolParam(description = "车队名称关键字；留空返回全部", required = false) String keyword,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "list_fleets", "查询车队", () -> {
            List<Map<String, Object>> rows =
                    fleets.findAll(keyword == null ? "" : keyword, session.scope()).stream()
                            .map(FleetTools::fleetBrief)
                            .toList();
            return ToolResults.page("fleets", rows, MAX_LIST, rows.size());
        });
    }

    @Tool(name = "list_geofences",
            description = "列出电子围栏的名称、中心、半径、限速与启停状态。回答「有哪些围栏」时用它。"
                    + "本工具不判断车辆是否在围栏内——那需要实时位置，不属于它的能力。")
    String listGeofences(ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "list_geofences", "查询电子围栏", () -> {
            List<Map<String, Object>> rows = geofences.findAll(session.scope()).stream()
                    .map(FleetTools::geofenceBrief)
                    .toList();
            return ToolResults.page("geofences", rows, MAX_LIST, rows.size());
        });
    }

    @Tool(name = "resolve_address",
            description = "把经纬度坐标转换成中文地址。当你手上只有坐标、而用户问的是「在哪」时用它——"
                    + "直接把一串经纬度念给用户是没有意义的。坐标必须是本平台其它工具返回的 lat/lng。")
    String resolveAddress(
            @ToolParam(description = "纬度") double lat,
            @ToolParam(description = "经度") double lng,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "resolve_address", "解析地址", () -> {
            if (!geocoder.available()) {
                return ToolResults.error("平台未配置地址解析服务，只能提供坐标");
            }
            return geocoder.address(lat, lng)
                    .<Object>map(address -> Map.of("lat", lat, "lng", lng, "address", address))
                    .orElseGet(() -> ToolResults.error("该坐标未能解析出地址"));
        });
    }

    /** 就地给若干行补上 address 字段。查不到就不加，绝不让地址失败连累主体数据。 */
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** 用户报的可能是设备号也可能是车牌，两种都试。 */
    private Optional<Vehicle> resolve(String vehicle, ToolSession session) {
        String trimmed = vehicle == null ? "" : vehicle.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(vehicles.get(trimmed, session.scope()));
        } catch (RuntimeException notFound) {
            String needle = trimmed.toLowerCase(Locale.ROOT);
            return vehicles.list(session.scope()).stream()
                    .filter(v -> matches(v, needle))
                    .findFirst();
        }
    }

    private static boolean matches(Vehicle vehicle, String needle) {
        return contains(vehicle.plateNo(), needle) || contains(vehicle.deviceId(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * 只保留模型用得上的字段。
     *
     * <p>告警与状态位的原始 JSON 串（{@code alarmJson}/{@code statusJson}）刻意剔除：它们是给
     * 界面渲染用的编码串，对回答问题没有帮助，纯占 token。WGS-84 原始坐标同理，保留 GCJ-02 即可。
     */
    private static Map<String, Object> brief(LiveStatus status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deviceId", status.deviceId());
        row.put("plateNo", status.plateNo());
        row.put("online", status.online());
        row.put("deviceTime", status.deviceTime());
        row.put("speedKph", status.speedKph());
        row.put("mileageKm", status.mileage());
        row.put("lat", status.gcjLat());
        row.put("lng", status.gcjLng());
        return row;
    }

    private static Map<String, Object> profileBrief(Vehicle vehicle) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deviceId", vehicle.deviceId());
        row.put("plateNo", vehicle.plateNo());
        row.put("plateColor", vehicle.plateColor());
        row.put("brand", vehicle.brand());
        row.put("channelCount", vehicle.channelCount());
        return row;
    }

    private static Map<String, Object> fleetBrief(FleetSummary summary) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", summary.fleet().name());
        row.put("totalVehicles", summary.totalVehicles());
        row.put("online", summary.online());
        row.put("moving", summary.moving());
        row.put("offline", summary.offline());
        row.put("openAlarms", summary.openAlarms());
        row.put("todayDistanceKm", summary.todayDistanceKm());
        return row;
    }

    /** 刻意不返回 {@code vehicleIds}：一个围栏可能绑几百台车，那串 ID 对回答问题毫无帮助。 */
    private static Map<String, Object> geofenceBrief(Geofence geofence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", geofence.name());
        row.put("radiusMeters", geofence.radiusMeters());
        row.put("enabled", geofence.enabled());
        row.put("speedLimitKph", geofence.speedLimitKph());
        row.put("assignedVehicleCount", geofence.assignedVehicleCount());
        return row;
    }
}
