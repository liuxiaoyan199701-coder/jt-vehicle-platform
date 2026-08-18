package io.github.jtplatform.simulator.signal;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 一次位置采样，用协议无关的单位表达。
 *
 * <p>坐标是**带符号的原始坐标系**十进制度。协议本身用无符号字段加两个符号标志位来表达南纬与西经，
 * 那套编码是 {@link SignalClient} 组装报文时的事——采样端不该被迫理解它。
 *
 * @param latitude 纬度，正北负南
 * @param longitude 经度，正东负西
 * @param altitudeMeters 高程，米
 * @param speedKph 速度，km/h
 * @param bearingDegrees 方向，正北为 0，顺时针，0..359
 * @param odometerMeters 累计里程，米。单调不减
 * @param deviceTime 终端本地时间——协议里这个字段是设备时间，不是平台时间
 */
public record LocationFix(
        double latitude,
        double longitude,
        int altitudeMeters,
        double speedKph,
        int bearingDegrees,
        double odometerMeters,
        LocalDateTime deviceTime) {

    public LocationFix {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            throw new IllegalArgumentException("坐标必须是有限值：" + latitude + "," + longitude);
        }
        if (!Double.isFinite(speedKph) || speedKph < 0) {
            throw new IllegalArgumentException("速度必须是非负的有限值：" + speedKph);
        }
        if (!Double.isFinite(odometerMeters) || odometerMeters < 0) {
            throw new IllegalArgumentException("里程必须是非负的有限值：" + odometerMeters);
        }
        Objects.requireNonNull(deviceTime, "deviceTime");
    }
}
