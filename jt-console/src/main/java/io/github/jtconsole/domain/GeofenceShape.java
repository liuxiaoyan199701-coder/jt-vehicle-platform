package io.github.jtconsole.domain;

import java.util.Locale;

/** 电子围栏形状。缺省或未知值回退到圆形，保证既有调用方零改动。 */
public enum GeofenceShape {
    CIRCLE,
    RECTANGLE,
    POLYGON,
    ROUTE;

    public static GeofenceShape fromWire(String value) {
        if (value == null || value.isBlank()) {
            return CIRCLE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "circle" -> CIRCLE;
            case "rectangle" -> RECTANGLE;
            case "polygon" -> POLYGON;
            case "route" -> ROUTE;
            default -> throw new IllegalArgumentException("不支持的围栏形状：" + value);
        };
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
