package io.github.jtplatform.boot.runtime;

import java.util.Locale;

public enum RuntimeRole {
    ALL,
    SIGNAL,
    MEDIA,
    API;

    public static RuntimeRole fromProperty(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidRole) {
            throw new IllegalArgumentException(
                    "jt.runtime.role must be one of all, signal, media, api", invalidRole);
        }
    }

    public String propertyValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
