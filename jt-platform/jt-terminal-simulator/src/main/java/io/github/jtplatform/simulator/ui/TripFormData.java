package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.TripConfig;

/**
 * 「行程」页的表单快照。文本框一律以字符串形式保存原样输入，校验发生在 {@link SimulatorFormData}。
 *
 * <p>起终点四项允许为空串，代表「不指定，用内置路线」——这是本页面唯一一处「空是合法值」的地方。
 */
public record TripFormData(
        boolean autoStart,
        String amapKey,
        String originLat,
        String originLng,
        String destinationLat,
        String destinationLng,
        String speedKph,
        String reportIntervalSeconds,
        boolean roundTrip) {

    public TripFormData {
        amapKey = normalize(amapKey);
        originLat = normalize(originLat);
        originLng = normalize(originLng);
        destinationLat = normalize(destinationLat);
        destinationLng = normalize(destinationLng);
        speedKph = normalize(speedKph);
        reportIntervalSeconds = normalize(reportIntervalSeconds);
    }

    public static TripFormData from(TripConfig trip) {
        return new TripFormData(
                trip.autoStart(),
                trip.amapKey(),
                number(trip.originLat()),
                number(trip.originLng()),
                number(trip.destinationLat()),
                number(trip.destinationLng()),
                number(trip.speedKph()),
                Integer.toString(trip.reportIntervalSeconds()),
                trip.roundTrip());
    }

    /** 起点与终点是否都留空——留空即使用内置路线。 */
    public boolean endpointsAllBlank() {
        return originLat.isEmpty() && originLng.isEmpty()
                && destinationLat.isEmpty() && destinationLng.isEmpty();
    }

    private static String number(Double value) {
        return value == null ? "" : number(value.doubleValue());
    }

    /**
     * 数字转文本。
     *
     * <p>刻意不用 {@code String.format("%f", …)}：它跟随系统区域设置，中文 Windows 下会输出
     * 逗号小数点（{@code 60,0}），而回填时的 {@link Double#parseDouble} 只认句点——配置会在
     * 一次「打开界面又保存」之后变成默认值。{@link Double#toString} 与区域设置无关。
     */
    private static String number(double value) {
        if (value == Math.rint(value) && Double.isFinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
