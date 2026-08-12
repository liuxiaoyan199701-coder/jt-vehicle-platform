package io.github.jtplatform.simulator.diagnostics;

import java.time.Instant;
import java.util.Objects;

public record LogEntry(Instant timestamp, LogLevel level, String component, String message) {
    public LogEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(level, "level");
        component = requireText(component, "component");
        message = requireText(message, "message");
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
