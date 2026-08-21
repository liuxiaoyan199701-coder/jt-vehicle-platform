package io.github.jtconsole.domain;

import java.util.Locale;

/** 告警规则类型。 */
public enum AlarmRuleType {
    SPEED_LIMIT,
    IDLE_TIMEOUT,
    FATIGUE_DRIVING;

    public static AlarmRuleType fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("告警规则类型不能为空");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SPEED_LIMIT" -> SPEED_LIMIT;
            case "IDLE_TIMEOUT" -> IDLE_TIMEOUT;
            case "FATIGUE_DRIVING" -> FATIGUE_DRIVING;
            default -> throw new IllegalArgumentException("不支持的告警规则类型：" + value);
        };
    }

    public String wireValue() {
        return name();
    }
}
