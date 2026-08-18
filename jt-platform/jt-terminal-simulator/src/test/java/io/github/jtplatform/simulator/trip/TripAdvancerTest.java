package io.github.jtplatform.simulator.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TripAdvancerTest {

    /** L 形路线：先正北一段，再正东一段。两段方位角是精确值，便于断言航向。 */
    private static final GeoPoint CORNER_START = new GeoPoint(31.0D, 121.0D);
    private static final GeoPoint CORNER_MIDDLE = new GeoPoint(31.01D, 121.0D);
    private static final GeoPoint CORNER_END = new GeoPoint(31.01D, 121.01D);

    private static Route lShapedRoute() {
        return Route.of(List.of(CORNER_START, CORNER_MIDDLE, CORNER_END), "L 形测试路线");
    }

    private static double northLegMeters() {
        return CoordinateTransform.distanceMeters(CORNER_START, CORNER_MIDDLE);
    }

    @Test
    void advancesFromOneSegmentIntoTheNext() {
        Route route = lShapedRoute();
        TripAdvancer advancer = new TripAdvancer(route, 36.0D, false); // 36 km/h = 10 m/s
        double intoSecondLeg = 400.0D;

        advancer.advance((northLegMeters() + intoSecondLeg) / 10.0D);

        assertEquals(northLegMeters() + intoSecondLeg, advancer.odometerMeters(), 1e-6D);
        // 已经拐过弯：纬度停在拐点上，经度开始东移。
        assertEquals(CORNER_MIDDLE.lat(), advancer.position().lat(), 1e-9D);
        assertTrue(advancer.position().lng() > CORNER_MIDDLE.lng());
        assertTrue(advancer.position().lng() < CORNER_END.lng());
    }

    @Test
    void reportsDueNorthOnTheFirstLegAndDueEastOnTheSecond() {
        Route route = lShapedRoute();
        TripAdvancer advancer = new TripAdvancer(route, 36.0D, false);

        assertEquals(0, advancer.bearing());

        advancer.advance((northLegMeters() + 400.0D) / 10.0D);

        assertEquals(90, advancer.bearing());
    }

    @Test
    void reversesBearingAfterTurningAroundAtTheEnd() {
        Route route = lShapedRoute();
        TripAdvancer outbound = new TripAdvancer(route, 36.0D, true);
        TripAdvancer returning = new TripAdvancer(route, 36.0D, true);
        double sameArcLength = northLegMeters() + 400.0D;

        outbound.advance(sameArcLength / 10.0D);
        // 走到同一段弧长，但已经过了终点正在往回开。
        returning.advance((2 * route.lengthMeters() - sameArcLength) / 10.0D);

        assertTrue(returning.returning());
        assertFalse(outbound.returning());
        assertEquals(outbound.position().lat(), returning.position().lat(), 1e-9D);
        assertEquals(outbound.position().lng(), returning.position().lng(), 1e-9D);
        assertEquals((outbound.bearing() + 180) % 360, returning.bearing());
    }

    @Test
    void keepsAccumulatingMileageAcrossLaps() {
        Route route = lShapedRoute();
        TripAdvancer advancer = new TripAdvancer(route, 36.0D, true);
        double cycle = route.lengthMeters() * 2;

        assertEquals(1, advancer.lap());

        advancer.advance(cycle * 1.5D / 10.0D);
        assertEquals(2, advancer.lap());
        // 掉头不重置里程——这正是「里程表」与「进度条」的区别。
        assertEquals(cycle * 1.5D, advancer.odometerMeters(), 1e-6D);

        advancer.advance(cycle / 10.0D);
        assertEquals(3, advancer.lap());
        assertEquals(cycle * 2.5D, advancer.odometerMeters(), 1e-6D);
    }

    @Test
    void stopsAtTheDestinationOnAOneWayTrip() {
        Route route = lShapedRoute();
        TripAdvancer advancer = new TripAdvancer(route, 36.0D, false);

        advancer.advance(route.lengthMeters() * 3 / 10.0D);

        assertTrue(advancer.finished());
        assertEquals(route.lengthMeters(), advancer.odometerMeters(), 1e-6D);
        assertEquals(CORNER_END.lat(), advancer.position().lat(), 1e-9D);
        assertEquals(CORNER_END.lng(), advancer.position().lng(), 1e-9D);
        // 停住的车不该还报着 60 km/h。
        assertEquals(0.0D, advancer.currentSpeedKph(), 0.0D);

        advancer.advance(600.0D);
        assertEquals(route.lengthMeters(), advancer.odometerMeters(), 1e-6D);
    }

    @Test
    void neverFinishesARoundTrip() {
        TripAdvancer advancer = new TripAdvancer(lShapedRoute(), 36.0D, true);

        advancer.advance(100_000.0D);

        assertFalse(advancer.finished());
        assertEquals(36.0D, advancer.currentSpeedKph(), 0.0D);
    }

    /**
     * 断线数分钟后重连，第一个时间增量会很大。折返用取模算而不是循环，因此这里不可能转不出来。
     */
    @Test
    void handlesAnEnormousTimeStepWithoutSpinning() {
        Route route = lShapedRoute();
        TripAdvancer advancer = new TripAdvancer(route, 120.0D, true);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> advancer.advance(1e9D));

        GeoPoint position = advancer.position();
        assertTrue(Double.isFinite(position.lat()) && Double.isFinite(position.lng()));
        // 无论快进多久，车都还在这条路线的经纬度范围内。
        assertTrue(position.lat() >= CORNER_START.lat() - 1e-9D
                && position.lat() <= CORNER_END.lat() + 1e-9D);
        assertTrue(position.lng() >= CORNER_START.lng() - 1e-9D
                && position.lng() <= CORNER_END.lng() + 1e-9D);
        assertTrue(advancer.bearing() >= 0 && advancer.bearing() < 360);
    }

    @Test
    void rejectsRoutesThatCannotBeDriven() {
        GeoPoint point = new GeoPoint(31.0D, 121.0D);

        assertThrows(IllegalArgumentException.class, () -> Route.of(List.of(), "空路线"));
        assertThrows(IllegalArgumentException.class, () -> Route.of(List.of(point), "单点路线"));
        // 同一个点重复多次，去重后只剩一个点。
        assertThrows(IllegalArgumentException.class,
                () -> Route.of(List.of(point, point, point), "原地踏步"));
        // 两点相距约 11 米，短于可模拟的最小长度。
        assertThrows(IllegalArgumentException.class,
                () -> Route.of(List.of(point, new GeoPoint(31.0001D, 121.0D)), "太短"));
    }

    @Test
    void rejectsUnusableSpeedsAndTimeSteps() {
        Route route = lShapedRoute();

        assertThrows(IllegalArgumentException.class, () -> new TripAdvancer(route, 0.0D, false));
        assertThrows(IllegalArgumentException.class, () -> new TripAdvancer(route, -10.0D, false));
        assertThrows(IllegalArgumentException.class,
                () -> new TripAdvancer(route, Double.NaN, false));

        TripAdvancer advancer = new TripAdvancer(route, 36.0D, false);
        assertThrows(IllegalArgumentException.class, () -> advancer.advance(-1.0D));
        assertThrows(IllegalArgumentException.class, () -> advancer.advance(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> advancer.advance(Double.POSITIVE_INFINITY));
    }

    @Test
    void dropsDuplicatePointsSoSeamsDoNotFlipTheHeading() {
        // 相邻分段的折线首尾重合，是路径规划返回值的常态。
        Route route = Route.of(
                List.of(CORNER_START, CORNER_MIDDLE, CORNER_MIDDLE, CORNER_END), "带接缝的路线");

        assertEquals(3, route.points().size());
        // 接缝处若留下零长度段，方位角会瞬间跳回正北。
        assertEquals(90, route.bearingAt(northLegMeters() + 1.0D));
    }
}
