package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ConfigValidation(
        Optional<SimulatorConfig> config,
        Map<ConfigField, String> errors) {

    public ConfigValidation {
        config = Objects.requireNonNull(config, "config");
        errors = Map.copyOf(Objects.requireNonNull(errors, "errors"));
        if (config.isPresent() == !errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "A validation result must contain either a config or field errors");
        }
    }

    public boolean valid() {
        return config.isPresent();
    }
}
