package io.github.jtconsole.domain;

/** 供位置判定使用的告警规则快照。 */
public record AlarmRuleCandidate(
        long id,
        String name,
        AlarmRuleType type,
        double thresholdKph,
        int durationMinutes,
        AlarmLevel level) {
}
