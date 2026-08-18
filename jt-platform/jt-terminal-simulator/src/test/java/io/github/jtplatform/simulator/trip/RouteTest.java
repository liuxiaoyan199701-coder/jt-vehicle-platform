package io.github.jtplatform.simulator.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteTest {

    /**
     * 折线串里**经度在前**。这与日常书写的「纬度,经度」相反，写反了不会报错，
     * 只会让车瞬移到地球另一侧——所以这条断言值得单独存在。
     */
    @Test
    void readsLongitudeBeforeLatitude() {
        List<GeoPoint> points = PolylineParser.parse("121.473701,31.230416");

        assertEquals(1, points.size());
        assertEquals(31.230416D, points.getFirst().lat(), 1e-9D);
        assertEquals(121.473701D, points.getFirst().lng(), 1e-9D);
    }

    @Test
    void skipsMalformedPointsInsteadOfAbandoningTheWholeLine() {
        // 路径规划一次返回上百个点，为其中一个畸形点丢掉整条路线不划算。
        List<GeoPoint> points = PolylineParser.parse(
                "121.4,31.2;;garbage;121.5,;,31.3;121.6,abc;121.7,31.4;  121.8,31.5  ");

        assertEquals(3, points.size());
        assertEquals(121.4D, points.get(0).lng(), 1e-9D);
        assertEquals(121.7D, points.get(1).lng(), 1e-9D);
        assertEquals(121.8D, points.get(2).lng(), 1e-9D);
    }

    @Test
    void dropsTheDuplicatedPointWhereTwoStepsMeet() {
        // 相邻分段的折线首尾本就是同一个点，这是路径规划返回值的常态而非异常。
        List<GeoPoint> merged = PolylineParser.parseAll(List.of(
                "121.40,31.20;121.41,31.21;121.42,31.22",
                "121.42,31.22;121.43,31.23",
                "",
                "121.43,31.23;121.44,31.24"));

        assertEquals(5, merged.size());
        assertEquals(121.44D, merged.getLast().lng(), 1e-9D);
    }

    @Test
    void interpolatesPositionAlongASegment() {
        GeoPoint start = new GeoPoint(31.0D, 121.0D);
        GeoPoint end = new GeoPoint(31.02D, 121.0D);
        Route route = Route.of(List.of(start, end), "正北直线");

        GeoPoint midpoint = route.pointAt(route.lengthMeters() / 2);

        assertEquals(31.01D, midpoint.lat(), 1e-6D);
        assertEquals(121.0D, midpoint.lng(), 1e-9D);
    }

    @Test
    void clampsArcLengthToTheEndsOfTheRoute() {
        GeoPoint start = new GeoPoint(31.0D, 121.0D);
        GeoPoint end = new GeoPoint(31.02D, 121.0D);
        Route route = Route.of(List.of(start, end), "正北直线");

        assertEquals(start.lat(), route.pointAt(-1000.0D).lat(), 1e-9D);
        assertEquals(end.lat(), route.pointAt(route.lengthMeters() * 5).lat(), 1e-9D);
        // 弧长正好等于总长时不能越过最后一个点去索引不存在的段。
        assertEquals(0, route.bearingAt(route.lengthMeters()));
    }

    @Test
    void convertsEncryptedInputSoTheConsoleDisplaysItUnshifted() {
        List<GeoPoint> encrypted = PolylineParser.parse(
                "121.473701,31.230416;121.499809,31.239692");

        Route route = Route.fromEncrypted(encrypted, "测试");

        // 转换确实发生了：存下来的点不等于传进来的点。
        assertNotEquals(encrypted.getFirst().lat(), route.points().getFirst().lat());
        // 而且经控制台正变换后正好落回原始点——这才是「不二次偏移」的完整含义。
        for (int i = 0; i < encrypted.size(); i++) {
            GeoPoint displayed = CoordinateTransform.toEncrypted(route.points().get(i));
            assertEquals(encrypted.get(i).lat(), displayed.lat(), 1e-7D);
            assertEquals(encrypted.get(i).lng(), displayed.lng(), 1e-7D);
        }
    }

    @Test
    void offersABuiltInRouteThatNeedsNoKeyAndNoNetwork() {
        Route route = BuiltInRoutes.offlineRoute();

        assertEquals(56, route.points().size());
        assertEquals(5760.0D, route.lengthMeters(), 100.0D);
        assertTrue(route.description().contains("内置"));
    }

    @Test
    void keepsTheBuiltInRouteInsideTheAreaItsEndpointsDescribe() {
        Route route = BuiltInRoutes.offlineRoute();

        for (GeoPoint point : route.points()) {
            GeoPoint displayed = CoordinateTransform.toEncrypted(point);
            // 抽稀后的折线应当仍然连接得起两个地标，偏差控制在几百米内。
            assertTrue(CoordinateTransform.distanceMeters(displayed, BuiltInRoutes.DEFAULT_ORIGIN)
                            < 8000.0D,
                    "内置折线上出现了远离起点的点：" + displayed);
            assertFalse(CoordinateTransform.outOfChina(displayed));
        }

        GeoPoint first = CoordinateTransform.toEncrypted(route.points().getFirst());
        GeoPoint last = CoordinateTransform.toEncrypted(route.points().getLast());
        assertTrue(CoordinateTransform.distanceMeters(first, BuiltInRoutes.DEFAULT_ORIGIN) < 300.0D);
        assertTrue(CoordinateTransform.distanceMeters(last, BuiltInRoutes.DEFAULT_DESTINATION)
                < 300.0D);
    }
}
