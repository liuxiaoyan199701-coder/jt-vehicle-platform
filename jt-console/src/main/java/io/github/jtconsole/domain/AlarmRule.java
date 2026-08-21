package io.github.jtconsole.domain;

import java.util.List;

/** 告警规则。thresholdKph 与 durationMinutes 的语义随类型：超速/疲劳看阈值，怠速/疲劳看时长。 */
public record AlarmRule(
        Long id,
        String name,
        AlarmRuleType type,
        double thresholdKph,
        int durationMinutes,
        AlarmLevel level,
        boolean enabled,
        List<String> vehicleIds,
        int assignedVehicleCount,
        Long tenantId,
        String createdAt,
        String updatedAt) {

    public AlarmRule {
        vehicleIds = vehicleIds == null ? List.of() : List.copyOf(vehicleIds);
    }
}
