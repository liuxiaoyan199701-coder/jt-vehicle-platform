package io.github.jtconsole.geo;

/**
 * WGS-84 与 GCJ-02（火星坐标）互转。
 *
 * <p>JT/T 808 终端上报的是 WGS-84 原始坐标，而高德、腾讯等国内地图使用 GCJ-02。
 * 直接把 WGS-84 打到高德地图上会整体偏移数百米，因此入库时同时保存两套坐标：
 * {@code lat/lng} 保留设备原值用于对外交换，{@code gcj_lat/gcj_lng} 供前端渲染。
 *
 * <p>中国境外不做偏移（国测局加密只作用于境内）。
 */
public final class CoordTransform {

    private static final double PI = 3.1415926535897932384626D;
    /** 克拉索夫斯基椭球长半轴 */
    private static final double A = 6378245.0D;
    /** 椭球第一偏心率平方 */
    private static final double EE = 0.00669342162296594323D;

    private CoordTransform() {
    }

    /**
     * @return 长度为 2 的数组，{@code [0]} 是纬度，{@code [1]} 是经度
     */
    public static double[] wgs84ToGcj02(double lat, double lng) {
        if (outOfChina(lat, lng)) {
            return new double[] {lat, lng};
        }
        double dLat = transformLat(lng - 105.0D, lat - 35.0D);
        double dLng = transformLng(lng - 105.0D, lat - 35.0D);
        double radLat = lat / 180.0D * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0D) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0D) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[] {lat + dLat, lng + dLng};
    }

    /**
     * GCJ-02 转回 WGS-84。无解析解，用一次反向偏移近似，误差约 1~2 米，足够展示用途。
     */
    public static double[] gcj02ToWgs84(double lat, double lng) {
        if (outOfChina(lat, lng)) {
            return new double[] {lat, lng};
        }
        double[] shifted = wgs84ToGcj02(lat, lng);
        return new double[] {lat * 2 - shifted[0], lng * 2 - shifted[1]};
    }

    /**
     * 粗略国界判断。边界外一律不偏移，宁可不转也不要把境外坐标转错。
     */
    public static boolean outOfChina(double lat, double lng) {
        return lng < 72.004D || lng > 137.8347D || lat < 0.8293D || lat > 55.8271D;
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

    /**
     * 两点间球面距离（米），用于轨迹里程统计。
     */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6371000.0D;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * earthRadius * Math.asin(Math.sqrt(h));
    }
}
