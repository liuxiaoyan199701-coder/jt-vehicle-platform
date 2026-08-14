package io.github.jtconsole.domain;

/**
 * 车辆档案。{@code deviceId} 是 JT/T 808 终端手机号，与网关投递信封的 deviceId 对应，
 * 同时也是全局主键——同一设备不可能同时属于两个租户。
 *
 * <p>{@code tenantId} 是「设备 → 租户」的唯一权威映射；{@code departmentId} 可空，
 * 未分配部门的车辆只对「本租户全部」范围的账号可见。
 */
public record Vehicle(
        String deviceId,
        String plateNo,
        String plateColor,
        String brand,
        int channelCount,
        String remark,
        Long tenantId,
        Long departmentId,
        String createdAt,
        String updatedAt) {
}
