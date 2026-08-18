package io.github.jtplatform.simulator.trip;

import io.github.jtplatform.simulator.config.TripConfig;
import java.util.List;
import java.util.Objects;

/**
 * 把行程配置解析成一条可以行驶的路线，附带一句说明。
 *
 * <p><b>{@link #resolve} 的签名里没有受检异常，而且它确实不会抛。</b>任何失败都收敛成一条保真度
 * 更低但仍然能跑的路线：用户永远看得到车在动，区别只是贴不贴路。这是刻意的——行程是个演示与联调
 * 工具，「因为拿不到路线所以什么都不做」是最没用的一种反应。
 *
 * <p>降级顺序：起终点齐全且密钥可用 → 路径规划折线；密钥缺失或调用失败 → 起终点直线；
 * 起终点未填 → 内置路线；起终点过近 → 内置路线。只填一端时，另一端用内置端点补齐。
 */
public final class RoutePlanner {

    private final DirectionsService directions;

    public RoutePlanner() {
        this(new AmapDirectionsClient());
    }

    public RoutePlanner(DirectionsService directions) {
        this.directions = Objects.requireNonNull(directions, "directions");
    }

    /** 解析路线。不抛异常——失败即降级。 */
    public RoutePlan resolve(TripConfig config) {
        Objects.requireNonNull(config, "config");

        if (!config.hasOrigin() && !config.hasDestination()) {
            return builtIn(config);
        }

        GeoPoint origin = config.hasOrigin()
                ? new GeoPoint(config.originLat(), config.originLng())
                : BuiltInRoutes.DEFAULT_ORIGIN;
        GeoPoint destination = config.hasDestination()
                ? new GeoPoint(config.destinationLat(), config.destinationLng())
                : BuiltInRoutes.DEFAULT_DESTINATION;

        String note = "";
        if (!config.hasOrigin()) {
            note = "起点未填，已用内置起点（上海人民广场）补齐。";
        } else if (!config.hasDestination()) {
            note = "终点未填，已用内置终点（上海陆家嘴）补齐。";
        }

        if (!config.hasAmapKey()) {
            return straightLine(origin, destination,
                    note + "未配置地图密钥，按起终点直线模拟（不贴合道路）；"
                            + "填入高德「Web 服务」Key 后即可沿真实道路行驶。",
                    true);
        }
        try {
            List<GeoPoint> polyline = directions.drivingRoute(origin, destination, config.amapKey());
            Route route = Route.fromEncrypted(polyline, "驾车路径规划");
            return new RoutePlan(route,
                    note + "已按真实道路规划：%s，%d 个折线点。".formatted(
                            kilometres(route), route.points().size()),
                    !note.isEmpty());
        } catch (AmapException failure) {
            return straightLine(origin, destination,
                    note + failure.getMessage() + "；已降级为起终点直线模拟，行程照常进行。", true);
        } catch (IllegalArgumentException degenerate) {
            // 规划成功但点太少或太短，与调用失败同样处理：能跑比跑得准更重要。
            return straightLine(origin, destination,
                    note + "规划结果不可用（" + degenerate.getMessage() + "），已降级为直线模拟。",
                    true);
        }
    }

    /**
     * 起终点都没填时的路线。
     *
     * <p>有密钥就照内置起终点规划一条贴路的，没有就用离线折线——**离线折线是「开箱即跑」的
     * 真正兑现点**，没有它，未配置密钥的首次体验会退化成两点直线，一眼就能看出是假的。
     */
    private RoutePlan builtIn(TripConfig config) {
        if (config.hasAmapKey()) {
            try {
                List<GeoPoint> polyline = directions.drivingRoute(
                        BuiltInRoutes.DEFAULT_ORIGIN, BuiltInRoutes.DEFAULT_DESTINATION,
                        config.amapKey());
                Route route = Route.fromEncrypted(polyline, BuiltInRoutes.DESCRIPTION);
                return new RoutePlan(route,
                        "未填起终点，使用内置起终点并按真实道路规划：%s。".formatted(kilometres(route)),
                        false);
            } catch (AmapException | IllegalArgumentException failure) {
                Route route = BuiltInRoutes.offlineRoute();
                return new RoutePlan(route,
                        "%s；已改用内置离线路线：%s。".formatted(failure.getMessage(), kilometres(route)),
                        true);
            }
        }
        Route route = BuiltInRoutes.offlineRoute();
        return new RoutePlan(route,
                "使用内置离线路线：%s，%s。填写起终点并配置地图密钥可模拟任意路线。"
                        .formatted(BuiltInRoutes.DESCRIPTION, kilometres(route)),
                false);
    }

    /**
     * 两点直线。起终点过近时无法构成可行驶的路线，退回内置路线而不是让车在原地抖动。
     */
    private RoutePlan straightLine(
            GeoPoint origin, GeoPoint destination, String explanation, boolean degraded) {
        try {
            Route route = Route.fromEncrypted(List.of(origin, destination), "起终点直线");
            return new RoutePlan(route, explanation + "（直线 %s）".formatted(kilometres(route)),
                    degraded);
        } catch (IllegalArgumentException tooShort) {
            Route route = BuiltInRoutes.offlineRoute();
            return new RoutePlan(route,
                    "起终点相距过近，无法构成可行驶的路线；已改用内置路线：%s。".formatted(kilometres(route)),
                    true);
        }
    }

    private static String kilometres(Route route) {
        return "%.1f km".formatted(route.lengthMeters() / 1000.0D);
    }
}
