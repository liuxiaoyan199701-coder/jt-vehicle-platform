package io.github.jtconsole.geo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeofenceGeometryTest {

    private static final List<double[]> SQUARE = List.of(
            new double[] {30.0, 120.0},
            new double[] {30.0, 120.1},
            new double[] {30.1, 120.1},
            new double[] {30.1, 120.0});

    @Test
    void pointInsidePolygon() {
        assertTrue(GeofenceGeometry.pointInPolygon(30.05, 120.05, SQUARE));
    }

    @Test
    void pointOutsidePolygon() {
        assertFalse(GeofenceGeometry.pointInPolygon(30.2, 120.05, SQUARE));
    }

    @Test
    void concavePolygon() {
        // 一个带凹口的 L 形多边形
        List<double[]> lShape = List.of(
                new double[] {30.0, 120.0},
                new double[] {30.0, 120.2},
                new double[] {30.1, 120.2},
                new double[] {30.1, 120.1},
                new double[] {30.2, 120.1},
                new double[] {30.2, 120.0});
        assertTrue(GeofenceGeometry.pointInPolygon(30.05, 120.15, lShape));
        // 凹口处（30.15, 120.15）在 L 形的缺口里（右上角）
        assertFalse(GeofenceGeometry.pointInPolygon(30.15, 120.15, lShape));
    }

    @Test
    void distanceToPolylineUsesNearestSegment() {
        List<double[]> line = List.of(
                new double[] {30.0, 120.0},
                new double[] {30.0, 120.1});
        // 距线段约 0.05 经度 ≈ 5.3 公里，明显大于 100 米
        double far = GeofenceGeometry.distanceToPolylineMeters(30.05, 120.05, line);
        assertTrue(far > 1000);
        // 正好在线段上
        double onLine = GeofenceGeometry.distanceToPolylineMeters(30.0, 120.05, line);
        assertTrue(onLine < 1);
    }
}
