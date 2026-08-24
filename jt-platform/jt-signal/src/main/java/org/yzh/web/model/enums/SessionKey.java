package org.yzh.web.model.enums;

public enum SessionKey {
    Device,
    /** T0100 正文里终端自报的「终端 ID」；注册被拒时也保留，作为诊断附注随事件带走。 */
    DiagnosticDeviceId,
    /**
     * T0100 报文头里的终端手机号；注册被拒时也保留。
     *
     * <p>没有它，注册失败设备后续的断开与协议错误就只剩终端 ID 可用，而平台没有任何一张表
     * 按终端 ID 建键——那些事件会永远归不到车辆上。
     */
    DiagnosticMobileNo
}
