package io.github.jtplatform.simulator.trip;

/**
 * 加密坐标系（地图服务用）与原始坐标系（协议传输用）的互转，外加球面距离与方位角。
 *
 * <p><b>为什么自带一份而不是复用平台公共模块里的同名工具</b>：控制台在显示时会对收到的原始坐标
 * 再做一次正向加密，所以模拟器要发的是「经控制台那一个具体函数正变换后正好落回地图原点」的值。
 * 而公共模块那份的境内边界与控制台那份并不一致，且它在生产代码里根本无人调用；控制台又是独立工程，
 * 依赖不到。复用一个既对不上又没人用的实现，只会制造「看起来共享了」的错觉。
 *
 * <p><b>为什么反向要迭代</b>：加密变换没有解析逆。常见做法是「在目标点上算一次偏移再减掉」，
 * 实测闭环残差约 2 米——车标会持续偏在道路旁边一个车道宽。这里用三轮不动点迭代把残差压到厘米级，
 * 车标能精确落在路面上。多出来的成本是每个点两次额外的三角函数计算，而路线只在构造时转换一次。
 */
public final class CoordinateTransform {

    /**
     * 下面三个常量逐位照抄控制台那份实现的字面量。
     *
     * <p>它们与 {@code Math.PI} 等写法在双精度下四舍五入到同一个值，但这里的目标是让两侧的正变换
     * 逐位一致，所以不留「大概相等」的余地——字面量不同就得每次重新论证一遍，抄一样的不用论证。
     */
    private static final double PI = 3.1415926535897932384626D;
    /** 克拉索夫斯基椭球长半轴，米。加密算法基于该椭球，不能换成 WGS-84 的 6378137。 */
    private static final double A = 6378245.0D;
    /** 该椭球的第一偏心率平方。 */
    private static final double EE = 0.00669342162296594323D;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0D;
    /** 三轮足以收敛到厘米级；再多轮不会更准，只是白算。 */
    private static final int INVERSE_ITERATIONS = 3;

    private CoordinateTransform() {
    }

    /**
     * 原始坐标 → 加密坐标。
     *
     * <p>这是控制台显示时所做的那次变换的等价实现，这里只用于反向迭代与测试对照，
     * 上报路径上不会调用它。
     */
    public static GeoPoint toEncrypted(GeoPoint plain) {
        if (outOfChina(plain)) {
            return plain;
        }
        double x = plain.lng() - 105.0D;
        double y = plain.lat() - 35.0D;
        double deltaLat = transformLat(x, y);
        double deltaLng = transformLng(x, y);
        double radLat = plain.lat() / 180.0D * PI;
        double magic = 1 - EE * Math.sin(radLat) * Math.sin(radLat);
        double sqrtMagic = Math.sqrt(magic);
        deltaLat = (deltaLat * 180.0D) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        deltaLng = (deltaLng * 180.0D) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new GeoPoint(plain.lat() + deltaLat, plain.lng() + deltaLng);
    }

    /**
     * 加密坐标 → 原始坐标，用不动点迭代求逆。
     *
     * <p>迭代式：以加密点为初值，每轮用「目标点减去当前解的正变换结果」修正当前解。
     * 因为偏移量在几百米尺度上变化极缓，这个迭代收敛得很快。
     */
    public static GeoPoint toPlain(GeoPoint encrypted) {
        if (outOfChina(encrypted)) {
            return encrypted;
        }
        double lat = encrypted.lat();
        double lng = encrypted.lng();
        for (int round = 0; round < INVERSE_ITERATIONS; round++) {
            GeoPoint forward = toEncrypted(new GeoPoint(lat, lng));
            lat += encrypted.lat() - forward.lat();
            lng += encrypted.lng() - forward.lng();
        }
        return new GeoPoint(lat, lng);
    }

    /**
     * 粗略国界判断。边界外不做偏移——境外坐标本来就不加密，硬转只会转错。
     *
     * <p>边界取值与控制台那份保持一致：这里的目的是让两侧对「哪些点要偏移」的判断相同，
     * 边界不一致会让边境附近的点一侧偏移一侧不偏移，误差瞬间跳到几百米。
     */
    public static boolean outOfChina(GeoPoint point) {
        return point.lng() < 72.004D || point.lng() > 137.8347D
                || point.lat() < 0.8293D || point.lat() > 55.8271D;
    }

    /** 两点间的球面距离，米。 */
    public static double distanceMeters(GeoPoint from, GeoPoint to) {
        double deltaLat = Math.toRadians(to.lat() - from.lat());
        double deltaLng = Math.toRadians(to.lng() - from.lng());
        double fromLat = Math.toRadians(from.lat());
        double toLat = Math.toRadians(to.lat());
        double h = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(fromLat) * Math.cos(toLat)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0D, Math.sqrt(h)));
    }

    /**
     * 起点指向终点的初始方位角，正北为 0，顺时针，0..359。
     *
     * <p>用 {@code floorMod} 一次处理负角与 360 归零，比手写两次取模不容易出错。
     */
    public static int bearingDegrees(GeoPoint from, GeoPoint to) {
        double fromLat = Math.toRadians(from.lat());
        double toLat = Math.toRadians(to.lat());
        double deltaLng = Math.toRadians(to.lng() - from.lng());
        double y = Math.sin(deltaLng) * Math.cos(toLat);
        double x = Math.cos(fromLat) * Math.sin(toLat)
                - Math.sin(fromLat) * Math.cos(toLat) * Math.cos(deltaLng);
        double degrees = Math.toDegrees(Math.atan2(y, x));
        return (int) Math.floorMod(Math.round(degrees), 360L);
    }

    private static double transformLat(double x, double y) {
        double result = -100.0D + 2.0D * x + 3.0D * y + 0.2D * y * y + 0.1D * x * y
                + 0.2D * Math.sqrt(Math.abs(x));
        result += (20.0D * Math.sin(6.0D * x * PI) + 20.0D * Math.sin(2.0D * x * PI)) * 2.0D / 3.0D;
        result += (20.0D * Math.sin(y * PI) + 40.0D * Math.sin(y / 3.0D * PI)) * 2.0D / 3.0D;
        result += (160.0D * Math.sin(y / 12.0D * PI) + 320.0D * Math.sin(y * PI / 30.0D)) * 2.0D / 3.0D;
        return result;
    }

    private static double transformLng(double x, double y) {
        double result = 300.0D + x + 2.0D * y + 0.1D * x * x + 0.1D * x * y
                + 0.1D * Math.sqrt(Math.abs(x));
        result += (20.0D * Math.sin(6.0D * x * PI) + 20.0D * Math.sin(2.0D * x * PI)) * 2.0D / 3.0D;
        result += (20.0D * Math.sin(x * PI) + 40.0D * Math.sin(x / 3.0D * PI)) * 2.0D / 3.0D;
        result += (150.0D * Math.sin(x / 12.0D * PI) + 300.0D * Math.sin(x / 30.0D * PI)) * 2.0D / 3.0D;
        return result;
    }
}
