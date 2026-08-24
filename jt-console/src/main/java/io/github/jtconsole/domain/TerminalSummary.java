package io.github.jtconsole.domain;

/**
 * 终端清单里的一行：台账（设备自报的事实）+ 档案（人工确认的归属）+ 在线状态。
 *
 * <p>{@code reportedPlate} 与 {@code plateNo} <b>刻意不合并成一列</b>：前者是终端自己说的，
 * 可以是错的；后者是运维确认过的。合并之后使用者就无从判断眼前这个车牌可不可信，
 * 而这恰恰是建档时最需要知道的事。
 *
 * @param archived  是否已建立车辆档案，由与 {@code vehicle} 的连接得出，不落列
 * @param plateNo   车辆档案里的车牌；未建档时为空
 * @param tenantId  归属租户；未建档时为空
 * @param online    当前是否在线，来自实时状态，与台账的 lastSeenAt 是两回事
 */
public record TerminalSummary(
        String deviceId,
        String terminalId,
        String makerId,
        String deviceModel,
        Integer provinceId,
        Integer cityId,
        String reportedPlate,
        Integer reportedColor,
        String protocolVersion,
        String firstSeenAt,
        String lastSeenAt,
        String lastResult,
        boolean archived,
        String plateNo,
        Long tenantId,
        boolean online,
        String onlineSeenAt) {
}
