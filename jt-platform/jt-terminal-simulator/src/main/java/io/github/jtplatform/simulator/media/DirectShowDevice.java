package io.github.jtplatform.simulator.media;

import java.util.Objects;

public record DirectShowDevice(Type type, String name, String alternativeName) {
    public DirectShowDevice {
        type = Objects.requireNonNull(type, "type");
        name = requireText(name, "name");
        alternativeName = alternativeName == null ? "" : alternativeName.trim();
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    public enum Type {
        VIDEO,
        AUDIO
    }
}
