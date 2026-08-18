package io.github.jtplatform.simulator.config;

import java.time.ZoneOffset;

/**
 * 模拟器全局的时间口径：**东八区（UTC+8）**。
 *
 * <p>覆盖三处：上报给平台的设备时间、写进日志文件的时间戳、界面上显示的日志时间。三处必须一致——
 * 否则排查时会同时看到三个不同的「现在」。
 *
 * <p><b>不用 {@code ZoneId.systemDefault()}</b>：模拟的是一台国内营运车辆的终端，它报的时间跟
 * 开发机器所在时区没有关系。曾经用系统时区，在一台设为美东时区的机器上跑，每个轨迹点都比真实时间
 * 早 13 小时——数据照常入库、地址也对，但平台按时间段查不到点，还会把一直在跑的车判成已离线数小时。
 *
 * <p>用固定偏移而不是 {@code Asia/Shanghai} 时区：中国不实行夏令时，偏移恒为 +08:00，固定偏移
 * 少一次时区库查找，也让「UTC+8」这个约定在类型上就是显式的。
 */
public final class TerminalTime {

    /** 东八区。中国无夏令时，该偏移恒定。 */
    public static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    private TerminalTime() {
    }
}
