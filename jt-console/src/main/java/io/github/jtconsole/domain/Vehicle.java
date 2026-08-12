package io.github.jtconsole.domain;

/**
 * 车辆档案。{@code deviceId} 是 JT/T 808 终端手机号，与网关投递信封的 deviceId 对应。
 */
public record Vehicle(
        String deviceId,
        String plateNo,
        String plateColor,
        String brand,
        int channelCount,
        String remark,
        String createdAt,
        String updatedAt) {
}
