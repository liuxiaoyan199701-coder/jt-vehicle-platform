package io.github.jtplatform.simulator.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 坐标换算的闭环验证。
 *
 * <p>这里刻意不去断言任何中间数值，而是断言业务保证本身：模拟器发出去的坐标，经**控制台那份实现**
 * 正变换显示之后，必须落回地图给出的原始点。因此测试里带了一份控制台正变换的抄本作参照
 * （{@link ConsoleReference}）——抄本不是重复实现，它是被验证的对象的一部分：控制台若改了公式，
 * 这份抄本与它对不上，闭环残差就会立刻超标。
 */
class CoordinateTransformTest {

    /** 1e-7 度约合 1.1 厘米，远小于路面车道宽度。 */
    private static final double CLOSED_LOOP_TOLERANCE_DEGREES = 1e-7D;

    /** 地图服务返回的加密坐标，散布在境内各处，覆盖不同纬度带。 */
    private static final GeoPoint[] ENCRYPTED_SAMPLES = {
        new GeoPoint(31.230416D, 121.473701D), // 上海 人民广场
        new GeoPoint(31.239692D, 121.499809D), // 上海 陆家嘴
        new GeoPoint(39.908722D, 116.397499D), // 北京 天安门
        new GeoPoint(23.129163D, 113.264435D), // 广州 越秀
        new GeoPoint(43.825592D, 87.616848D),  // 乌鲁木齐
        new GeoPoint(45.803775D, 126.534967D), // 哈尔滨
        new GeoPoint(30.657401D, 104.065861D), // 成都
    };

    @Test
    void inverseLandsBackOnTheOriginalPointUnderTheConsoleForwardTransform() {
        for (GeoPoint encrypted : ENCRYPTED_SAMPLES) {
            GeoPoint plain = CoordinateTransform.toPlain(encrypted);
            GeoPoint roundTripped = ConsoleReference.forward(plain);

            assertEquals(encrypted.lat(), roundTripped.lat(), CLOSED_LOOP_TOLERANCE_DEGREES,
                    "纬度闭环超差 @ " + encrypted);
            assertEquals(encrypted.lng(), roundTripped.lng(), CLOSED_LOOP_TOLERANCE_DEGREES,
                    "经度闭环超差 @ " + encrypted);
        }
    }

    /**
     * 自带的正变换必须与控制台那份逐位一致——否则反向迭代收敛到的是「我们自己的」不动点，
     * 闭环测试会通过，而线上车标照样偏。这条测试守的就是这个假象。
     */
    @Test
    void forwardTransformMatchesTheConsoleBitForBit() {
        for (GeoPoint sample : ENCRYPTED_SAMPLES) {
            GeoPoint ours = CoordinateTransform.toEncrypted(sample);
            GeoPoint theirs = ConsoleReference.forward(sample);

            assertEquals(theirs.lat(), ours.lat(), 0.0D, "纬度正变换与控制台不一致 @ " + sample);
            assertEquals(theirs.lng(), ours.lng(), 0.0D, "经度正变换与控制台不一致 @ " + sample);
        }
    }

    /**
     * 用一次近似逆作对照，说明为什么值得多迭代两轮。
     *
     * <p>一次近似逆正是控制台自己 {@code gcj02ToWgs84} 的做法。断言写成「近似逆明显更差」而不是
     * 某个具体米数：数值会随采样点变化，而「差一个数量级以上」这个结论不会。若哪天近似逆也能到
     * 厘米级，这条测试失败反而是好事——那说明可以少写一个迭代循环。
     */
    @Test
    void iterativeInverseBeatsTheSingleShotApproximationByAnOrderOfMagnitude() {
        double worstIterative = 0.0D;
        double worstSingleShot = 0.0D;

        for (GeoPoint encrypted : ENCRYPTED_SAMPLES) {
            GeoPoint iterative = ConsoleReference.forward(CoordinateTransform.toPlain(encrypted));
            GeoPoint singleShot = ConsoleReference.forward(ConsoleReference.approximateInverse(encrypted));

            worstIterative = Math.max(worstIterative,
                    CoordinateTransform.distanceMeters(encrypted, iterative));
            worstSingleShot = Math.max(worstSingleShot,
                    CoordinateTransform.distanceMeters(encrypted, singleShot));
        }

        assertTrue(worstIterative < 0.05D,
                "迭代逆的闭环残差应在厘米级，实测 " + worstIterative + " 米");
        assertTrue(worstSingleShot > worstIterative * 10,
                "一次近似逆应明显更差，实测迭代 " + worstIterative + " 米 / 近似 " + worstSingleShot + " 米");
    }

    @Test
    void leavesPointsOutsideChinaUntouched() {
        GeoPoint tokyo = new GeoPoint(35.6762D, 139.6503D);
        GeoPoint sydney = new GeoPoint(-33.8688D, 151.2093D);

        assertSame(tokyo, CoordinateTransform.toPlain(tokyo));
        assertSame(tokyo, CoordinateTransform.toEncrypted(tokyo));
        assertSame(sydney, CoordinateTransform.toPlain(sydney));
        assertSame(sydney, CoordinateTransform.toEncrypted(sydney));
    }

    /**
     * 国界判断必须与控制台一致。两侧不一致时，边境附近的点会一侧偏移一侧不偏移，
     * 误差从厘米级直接跳到几百米，而且只在特定地区复现——极难排查。
     */
    @Test
    void chinaBoundsMatchTheConsole() {
        GeoPoint[] probes = {
            new GeoPoint(0.8292D, 100.0D), new GeoPoint(0.8294D, 100.0D),
            new GeoPoint(55.8270D, 100.0D), new GeoPoint(55.8272D, 100.0D),
            new GeoPoint(30.0D, 72.0039D), new GeoPoint(30.0D, 72.0041D),
            new GeoPoint(30.0D, 137.8346D), new GeoPoint(30.0D, 137.8348D),
        };

        for (GeoPoint probe : probes) {
            assertEquals(ConsoleReference.outOfChina(probe), CoordinateTransform.outOfChina(probe),
                    "境内外判定与控制台不一致 @ " + probe);
        }
    }

    @Test
    void measuresDistanceAndBearingBetweenTwoPoints() {
        GeoPoint peoplesSquare = new GeoPoint(31.230416D, 121.473701D);
        GeoPoint lujiazui = new GeoPoint(31.239692D, 121.499809D);

        // 人民广场到陆家嘴直线约 2.7 公里，方位角指向东偏北。
        assertEquals(2700.0D, CoordinateTransform.distanceMeters(peoplesSquare, lujiazui), 150.0D);
        assertEquals(67, CoordinateTransform.bearingDegrees(peoplesSquare, lujiazui), 3);

        assertEquals(0.0D, CoordinateTransform.distanceMeters(peoplesSquare, peoplesSquare), 0.0D);
    }

    @Test
    void reportsBearingAsCompassDegreesWithoutNegativesOrThreeSixty() {
        GeoPoint origin = new GeoPoint(31.0D, 121.0D);

        assertEquals(0, CoordinateTransform.bearingDegrees(origin, new GeoPoint(31.01D, 121.0D)));
        assertEquals(90, CoordinateTransform.bearingDegrees(origin, new GeoPoint(31.0D, 121.01D)));
        assertEquals(180, CoordinateTransform.bearingDegrees(origin, new GeoPoint(30.99D, 121.0D)));
        // 正西是 270 而不是 -90——负角会让协议里的方向字段编码成天文数字。
        assertEquals(270, CoordinateTransform.bearingDegrees(origin, new GeoPoint(31.0D, 120.99D)));
    }

    /**
     * 控制台 {@code io.github.jtconsole.geo.CoordTransform} 的抄本，逐字保留其常量与公式。
     *
     * <p>控制台是与本工程并列的独立 Maven 工程，依赖不到，只能抄。抄本仅限本测试使用，
     * 生产代码不得引用。
     */
    private static final class ConsoleReference {
        private static final double PI = 3.1415926535897932384626D;
        private static final double A = 6378245.0D;
        private static final double EE = 0.00669342162296594323D;

        static GeoPoint forward(GeoPoint plain) {
            double lat = plain.lat();
            double lng = plain.lng();
            if (outOfChina(plain)) {
                return plain;
            }
            double dLat = transformLat(lng - 105.0D, lat - 35.0D);
            double dLng = transformLng(lng - 105.0D, lat - 35.0D);
            double radLat = lat / 180.0D * PI;
            double magic = Math.sin(radLat);
            magic = 1 - EE * magic * magic;
            double sqrtMagic = Math.sqrt(magic);
            dLat = (dLat * 180.0D) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
            dLng = (dLng * 180.0D) / (A / sqrtMagic * Math.cos(radLat) * PI);
            return new GeoPoint(lat + dLat, lng + dLng);
        }

        /** 控制台自己的反向实现：一次反向偏移，注释称「误差约 1~2 米，足够展示用途」。 */
        static GeoPoint approximateInverse(GeoPoint encrypted) {
            if (outOfChina(encrypted)) {
                return encrypted;
            }
            GeoPoint shifted = forward(encrypted);
            return new GeoPoint(
                    encrypted.lat() * 2 - shifted.lat(),
                    encrypted.lng() * 2 - shifted.lng());
        }

        static boolean outOfChina(GeoPoint point) {
            return point.lng() < 72.004D || point.lng() > 137.8347D
                    || point.lat() < 0.8293D || point.lat() > 55.8271D;
        }

        private static double transformLat(double x, double y) {
            double ret = -100.0D + 2.0D * x + 3.0D * y + 0.2D * y * y + 0.1D * x * y
                    + 0.2D * Math.sqrt(Math.abs(x));
            ret += (20.0D * Math.sin(6.0D * x * PI) + 20.0D * Math.sin(2.0D * x * PI)) * 2.0D / 3.0D;
            ret += (20.0D * Math.sin(y * PI) + 40.0D * Math.sin(y / 3.0D * PI)) * 2.0D / 3.0D;
            ret += (160.0D * Math.sin(y / 12.0D * PI) + 320.0D * Math.sin(y * PI / 30.0D)) * 2.0D / 3.0D;
            return ret;
        }

        private static double transformLng(double x, double y) {
            double ret = 300.0D + x + 2.0D * y + 0.1D * x * x + 0.1D * x * y
                    + 0.1D * Math.sqrt(Math.abs(x));
            ret += (20.0D * Math.sin(6.0D * x * PI) + 20.0D * Math.sin(2.0D * x * PI)) * 2.0D / 3.0D;
            ret += (20.0D * Math.sin(x * PI) + 40.0D * Math.sin(x / 3.0D * PI)) * 2.0D / 3.0D;
            ret += (150.0D * Math.sin(x / 12.0D * PI) + 300.0D * Math.sin(x / 30.0D * PI)) * 2.0D / 3.0D;
            return ret;
        }
    }
}
