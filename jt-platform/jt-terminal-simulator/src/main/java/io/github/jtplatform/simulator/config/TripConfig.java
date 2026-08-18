package io.github.jtplatform.simulator.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模拟行程的配置。
 *
 * <p><b>本记录刻意比同包其它配置宽容</b>：兄弟记录对非法值一律抛异常，而这里一律回落默认值。
 * 原因是加载路径的行为不对称——配置反序列化一旦抛异常，上层会整份回落到默认配置，用户的信令地址、
 * 终端号、编码器路径、码流参数会**一起丢掉**。为一个模拟速度写错而清空用户全部配置，代价完全不成比例。
 *
 * <p>手工改坏的值会被悄悄换成默认值，但用户不会毫无察觉：状态栏会显示实际生效的路线来源
 * （例如「使用内置路线」），与预期不符时立刻能看出来。
 *
 * <p>起终点用装箱的 {@link Double}：原始 {@code double} 没有办法表达「用户没填」——0 是赤道上
 * 几内亚湾里的一个真实坐标，不能拿来当哨兵值。
 */
public record TripConfig(
        boolean autoStart,
        String amapKey,
        Double originLat,
        Double originLng,
        Double destinationLat,
        Double destinationLng,
        double speedKph,
        int reportIntervalSeconds,
        boolean roundTrip) {

    public static final double DEFAULT_SPEED_KPH = 60.0D;
    public static final int DEFAULT_REPORT_INTERVAL_SECONDS = 10;

    public static final double MIN_SPEED_KPH = 1.0D;
    public static final double MAX_SPEED_KPH = 300.0D;
    public static final int MIN_REPORT_INTERVAL_SECONDS = 1;
    public static final int MAX_REPORT_INTERVAL_SECONDS = 600;

    public TripConfig {
        amapKey = amapKey == null ? "" : amapKey.trim();
        originLat = validLatitude(originLat);
        originLng = validLongitude(originLng);
        destinationLat = validLatitude(destinationLat);
        destinationLng = validLongitude(destinationLng);
        // 只有一半的坐标等于没有坐标：留着半个点，后面每个用到的地方都得再判一次。
        if (originLat == null || originLng == null) {
            originLat = null;
            originLng = null;
        }
        if (destinationLat == null || destinationLng == null) {
            destinationLat = null;
            destinationLng = null;
        }
        speedKph = inRange(speedKph, MIN_SPEED_KPH, MAX_SPEED_KPH, DEFAULT_SPEED_KPH);
        reportIntervalSeconds = (int) inRange(reportIntervalSeconds,
                MIN_REPORT_INTERVAL_SECONDS, MAX_REPORT_INTERVAL_SECONDS,
                DEFAULT_REPORT_INTERVAL_SECONDS);
    }

    /**
     * 反序列化入口，所有分量都用装箱类型接收。
     *
     * <p>没有它，缺一个字段就加载不了：记录的规范构造器要求每个分量都有值，而反序列化器
     * **拒绝把 null 塞进原始类型**，于是「配置里少了 {@code roundTrip} 这一行」会直接抛异常——
     * 上层再把整份配置回落成默认值。装箱之后「字段不存在」是一个可以表达、也可以填默认值的状态，
     * 这个记录也就能随版本增删字段而不再破坏用户已有的配置文件。
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static TripConfig fromJson(
            @JsonProperty("autoStart") Boolean autoStart,
            @JsonProperty("amapKey") String amapKey,
            @JsonProperty("originLat") Double originLat,
            @JsonProperty("originLng") Double originLng,
            @JsonProperty("destinationLat") Double destinationLat,
            @JsonProperty("destinationLng") Double destinationLng,
            @JsonProperty("speedKph") Double speedKph,
            @JsonProperty("reportIntervalSeconds") Integer reportIntervalSeconds,
            @JsonProperty("roundTrip") Boolean roundTrip) {
        TripConfig defaults = defaults();
        return new TripConfig(
                autoStart == null ? defaults.autoStart() : autoStart,
                amapKey,
                originLat,
                originLng,
                destinationLat,
                destinationLng,
                speedKph == null ? defaults.speedKph() : speedKph,
                reportIntervalSeconds == null
                        ? defaults.reportIntervalSeconds() : reportIntervalSeconds,
                roundTrip == null ? defaults.roundTrip() : roundTrip);
    }

    public static TripConfig defaults() {
        return new TripConfig(
                false,
                "",
                null,
                null,
                null,
                null,
                DEFAULT_SPEED_KPH,
                DEFAULT_REPORT_INTERVAL_SECONDS,
                true);
    }

    /** 是否配置了可用于路径规划的密钥。 */
    public boolean hasAmapKey() {
        return !amapKey.isEmpty();
    }

    /** 起点是否完整可用。 */
    public boolean hasOrigin() {
        return originLat != null && originLng != null;
    }

    /** 终点是否完整可用。 */
    public boolean hasDestination() {
        return destinationLat != null && destinationLng != null;
    }

    private static Double validLatitude(Double value) {
        return value != null && Double.isFinite(value) && value >= -90.0D && value <= 90.0D
                ? value : null;
    }

    private static Double validLongitude(Double value) {
        return value != null && Double.isFinite(value) && value >= -180.0D && value <= 180.0D
                ? value : null;
    }

    /**
     * 越界或非有限值一律换成默认值。
     *
     * <p>「缺失」在 JSON 反序列化里表现为 0，而 0 恰好也是越界值，因此这一条同时覆盖了
     * 「字段不存在」与「字段被改坏」两种情况——不需要分开处理。
     */
    private static double inRange(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
    }
}
