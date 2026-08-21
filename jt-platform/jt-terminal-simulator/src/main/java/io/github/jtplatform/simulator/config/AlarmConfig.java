package io.github.jtplatform.simulator.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 持久化的告警模拟参数；置位集合本身可随演示保存，默认全部解除。 */
public record AlarmConfig(int warnBits, int overspeedKph) {
    public static final int DEFAULT_OVERSPEED_KPH = 80;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static AlarmConfig fromJson(@JsonProperty("warnBits") Integer warnBits,
                                @JsonProperty("overspeedKph") Integer overspeedKph) {
        return new AlarmConfig(warnBits == null ? 0 : warnBits,
                overspeedKph == null ? DEFAULT_OVERSPEED_KPH : overspeedKph);
    }

    public AlarmConfig {
        if (overspeedKph < 1 || overspeedKph > 300) {
            overspeedKph = DEFAULT_OVERSPEED_KPH;
        }
    }

    public static AlarmConfig defaults() {
        return new AlarmConfig(0, DEFAULT_OVERSPEED_KPH);
    }
}
