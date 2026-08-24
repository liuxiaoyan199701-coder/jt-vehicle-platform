package io.github.jtconsole.domain;

import com.fasterxml.jackson.annotation.JsonValue;
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

    /**
     * 对外一律用小写。数据库、AI 工具与前端类型（{@code 'circle' | 'polygon' | ...}）
     * 用的都是这个形态，唯独 REST 响应此前直接序列化枚举名给出大写，
     * 前端的 {@code shape === 'circle'} 因而恒为假——圆形围栏在地图上画不出来。
     */
    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
