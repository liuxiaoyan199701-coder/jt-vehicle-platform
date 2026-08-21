package io.github.jtplatform.simulator.signal;

import java.util.List;

/**
 * 0x0200 warnBit 常用位清单，位号和名称直接对照 JT/T 808 位置汇报定义。
 * 未在协议库中重复定义，避免模拟器与协议实现产生两套位号。
 */
public record AlarmDefinition(int bit, String name) {
    public static final int EMERGENCY_BIT = 0;
    public static final int OVERSPEED_BIT = 1;
    public static final int FATIGUE_BIT = 2;
    public static final int GNSS_MODULE_FAULT_BIT = 6;
    public static final int GNSS_ANTENNA_FAULT_BIT = 7;

    public static final List<AlarmDefinition> COMMON = List.of(
            new AlarmDefinition(EMERGENCY_BIT, "紧急报警 (bit0)"),
            new AlarmDefinition(OVERSPEED_BIT, "超速报警 (bit1)"),
            new AlarmDefinition(FATIGUE_BIT, "疲劳驾驶 (bit2)"),
            new AlarmDefinition(GNSS_MODULE_FAULT_BIT, "GNSS 模块故障 (bit6)"),
            new AlarmDefinition(GNSS_ANTENNA_FAULT_BIT, "GNSS 天线未接/短路 (bit7)"));

    public AlarmDefinition {
        if (bit < 0 || bit > 31) {
            throw new IllegalArgumentException("alarm bit must be in range 0..31");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("alarm name must not be blank");
        }
    }

    public int mask() {
        return 1 << bit;
    }
}
