package io.github.jtplatform.simulator.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.TripConfig;
import org.junit.jupiter.api.Test;

/**
 * 降级链的五条分支。每条都必须产出一条能跑的路线——{@code resolve} 从不抛异常。
 */
class RoutePlannerTest {

    private static final double SHANGHAI_LAT = 31.230416D;
    private static final double SHANGHAI_LNG = 121.473701D;
    private static final double LUJIAZUI_LAT = 31.239692D;
    private static final double LUJIAZUI_LNG = 121.499809D;

    @Test
    void drawsAStraightLineWhenEndpointsAreGivenButNoKeyIs() {
        RoutePlan plan = new RoutePlanner(failingClient()).resolve(
                trip("", SHANGHAI_LAT, SHANGHAI_LNG, LUJIAZUI_LAT, LUJIAZUI_LNG));

        assertEquals("起终点直线", plan.route().description());
        assertEquals(2, plan.route().points().size());
        assertTrue(plan.degraded());
        // 说明必须给出恢复方式，而不只是陈述现状。
        assertTrue(plan.explanation().contains("Web 服务"), plan.explanation());
    }

    @Test
    void usesTheOfflineBuiltInRouteWhenNothingIsConfiguredAtAll() {
        // 没密钥、没起终点、没网络——这是首次打开时的默认状态，必须直接能跑。
        RoutePlan plan = new RoutePlanner(failingClient()).resolve(TripConfig.defaults());

        assertEquals(BuiltInRoutes.DESCRIPTION, plan.route().description());
        assertEquals(56, plan.route().points().size());
        assertFalse(plan.degraded(), "默认配置跑内置路线是正常路径，不是降级");
    }

    @Test
    void fallsBackToAStraightLineWhenTheServiceCallFails() {
        RoutePlan plan = new RoutePlanner(failingClient()).resolve(
                trip("some-key", SHANGHAI_LAT, SHANGHAI_LNG, LUJIAZUI_LAT, LUJIAZUI_LNG));

        assertEquals("起终点直线", plan.route().description());
        assertTrue(plan.degraded());
        assertTrue(plan.explanation().contains("服务挂了"), plan.explanation());
        // 降级说明要让人知道行程并没有停。
        assertTrue(plan.explanation().contains("照常"), plan.explanation());
    }

    @Test
    void fallsBackToTheOfflineRouteWhenTheServiceFailsAndNoEndpointsWereGiven() {
        RoutePlan plan = new RoutePlanner(failingClient()).resolve(
                trip("some-key", null, null, null, null));

        assertEquals(BuiltInRoutes.DESCRIPTION, plan.route().description());
        assertTrue(plan.degraded());
    }

    @Test
    void usesTheBuiltInRouteWhenTheTwoEndpointsAreEssentiallyTheSamePlace() {
        // 表单会先拦下这种输入；手工改配置文件绕过表单时，这里再兜一次。
        RoutePlan plan = new RoutePlanner(failingClient()).resolve(
                trip("", SHANGHAI_LAT, SHANGHAI_LNG, SHANGHAI_LAT + 0.0001D, SHANGHAI_LNG));

        assertEquals(BuiltInRoutes.DESCRIPTION, plan.route().description());
        assertTrue(plan.degraded());
        assertTrue(plan.explanation().contains("过近"), plan.explanation());
    }

    @Test
    void fillsInTheMissingEndpointFromTheBuiltInRoute() {
        RoutePlan onlyOrigin = new RoutePlanner(failingClient()).resolve(
                trip("", SHANGHAI_LAT, SHANGHAI_LNG, null, null));
        RoutePlan onlyDestination = new RoutePlanner(failingClient()).resolve(
                trip("", null, null, LUJIAZUI_LAT, LUJIAZUI_LNG));

        assertTrue(onlyOrigin.explanation().contains("终点未填"), onlyOrigin.explanation());
        assertTrue(onlyDestination.explanation().contains("起点未填"),
                onlyDestination.explanation());
        // 补齐之后仍然是一条能跑的直线，而不是退回内置路线。
        assertEquals("起终点直线", onlyOrigin.route().description());
        assertEquals("起终点直线", onlyDestination.route().description());
    }

    @Test
    void usesTheServicePolylineWhenBothEndpointsAndAKeyAreAvailable() {
        RoutePlan plan = new RoutePlanner(stubClient()).resolve(
                trip("good-key", SHANGHAI_LAT, SHANGHAI_LNG, LUJIAZUI_LAT, LUJIAZUI_LNG));

        assertEquals("驾车路径规划", plan.route().description());
        assertEquals(3, plan.route().points().size());
        assertFalse(plan.degraded());
        assertTrue(plan.explanation().contains("真实道路"), plan.explanation());
    }

    /**
     * 上报的坐标经控制台正变换显示后必须落回地图原点，否则车标会平行偏在道路一侧。
     */
    @Test
    void convertsServicePointsSoTheConsoleShowsThemUnshifted() {
        RoutePlan plan = new RoutePlanner(stubClient()).resolve(
                trip("good-key", SHANGHAI_LAT, SHANGHAI_LNG, LUJIAZUI_LAT, LUJIAZUI_LNG));

        GeoPoint displayed = CoordinateTransform.toEncrypted(plan.route().points().getFirst());

        assertEquals(31.2304D, displayed.lat(), 1e-6D);
        assertEquals(121.4737D, displayed.lng(), 1e-6D);
    }

    private static TripConfig trip(
            String key, Double originLat, Double originLng,
            Double destinationLat, Double destinationLng) {
        return new TripConfig(false, key, originLat, originLng, destinationLat, destinationLng,
                60.0D, 10, true);
    }

    /** 任何调用都失败——覆盖「没网 / Key 无效 / 配额用完」这一整类情况。 */
    private static DirectionsService failingClient() {
        return (origin, destination, key) -> {
            throw new AmapException("服务挂了");
        };
    }

    private static DirectionsService stubClient() {
        return (origin, destination, key) ->
                PolylineParser.parse("121.4737,31.2304;121.4800,31.2320;121.4900,31.2360");
    }
}
