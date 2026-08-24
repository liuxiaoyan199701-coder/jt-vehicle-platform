package io.github.jtconsole.domain;

/**
 * 一台连接过网关的终端，及其**自报**的身份信息。
 *
 * <p>自报即不可信：车牌、型号、制造商都是终端自己填的，可以是错的，也可以是空的。
 * 台账记录的是「它说自己是谁」这个事实，用于发现设备与预填建档表单；
 * 「它是谁」由车辆档案（{@link Vehicle}）人工确认，两者刻意分开。
 *
 * @param deviceId      终端手机号，与 {@code vehicle.device_id} 同键，全平台主键
 * @param terminalId    0x0100 正文里自报的终端 ID，与手机号是两个东西，只鉴权未注册时为空
 * @param reportedPlate 自报车牌，仅供建档预填；与档案车牌分开呈现，不可混为一谈
 * @param lastSeenAt    最近一次**注册或鉴权**的时间，不是「最近在线」——
 *                      长连不断的终端不会刷新它，在线与否看 {@code device_status}
 * @param lastResult    最近一次注册/鉴权的结局，用来认出「一直连不上」的终端
 */
public record Terminal(
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
        String updatedAt) {
}
