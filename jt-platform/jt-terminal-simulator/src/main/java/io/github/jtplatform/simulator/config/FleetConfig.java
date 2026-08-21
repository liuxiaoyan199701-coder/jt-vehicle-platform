package io.github.jtplatform.simulator.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 车队模式配置；模板仍是外层 {@link SimulatorConfig}，本记录只保存派生规则。 */
public record FleetConfig(
        boolean enabled,
        int vehicleCount,
        boolean incrementDeviceId,
        boolean incrementMobileNo,
        boolean incrementPlateNo,
        int departureIntervalSeconds) {

    public static final int MAX_VEHICLES = 20;
    public static final int DEFAULT_DEPARTURE_INTERVAL_SECONDS = 15;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static FleetConfig fromJson(
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("vehicleCount") Integer vehicleCount,
            @JsonProperty("incrementDeviceId") Boolean incrementDeviceId,
            @JsonProperty("incrementMobileNo") Boolean incrementMobileNo,
            @JsonProperty("incrementPlateNo") Boolean incrementPlateNo,
            @JsonProperty("departureIntervalSeconds") Integer departureIntervalSeconds) {
        FleetConfig defaults = defaults();
        return new FleetConfig(
                enabled == null ? defaults.enabled() : enabled,
                vehicleCount == null ? defaults.vehicleCount() : vehicleCount,
                incrementDeviceId == null ? defaults.incrementDeviceId() : incrementDeviceId,
                incrementMobileNo == null ? defaults.incrementMobileNo() : incrementMobileNo,
                incrementPlateNo == null ? defaults.incrementPlateNo() : incrementPlateNo,
                departureIntervalSeconds == null
                        ? defaults.departureIntervalSeconds() : departureIntervalSeconds);
    }

    public FleetConfig {
        if (vehicleCount < 1 || vehicleCount > MAX_VEHICLES) {
            throw new IllegalArgumentException("vehicleCount must be in range 1.." + MAX_VEHICLES);
        }
        if (departureIntervalSeconds < 0 || departureIntervalSeconds > 3_600) {
            throw new IllegalArgumentException("departureIntervalSeconds must be in range 0..3600");
        }
    }

    public static FleetConfig defaults() {
        return new FleetConfig(false, 1, true, true, true, DEFAULT_DEPARTURE_INTERVAL_SECONDS);
    }
}
