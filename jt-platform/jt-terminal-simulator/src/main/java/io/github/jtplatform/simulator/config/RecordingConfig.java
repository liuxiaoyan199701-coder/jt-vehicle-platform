package io.github.jtplatform.simulator.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 模拟设备上的录像资源窗口。时间文本使用 HH:mm，也支持默认值使用的 NOW-2H/NOW。 */
public record RecordingConfig(
        int resourceCount,
        String startTime,
        String endTime,
        int channel) {

    public static final int DEFAULT_RESOURCE_COUNT = 4;
    public static final String DEFAULT_START_TIME = "NOW-2H";
    public static final String DEFAULT_END_TIME = "NOW";

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static RecordingConfig fromJson(
            @JsonProperty("resourceCount") Integer resourceCount,
            @JsonProperty("startTime") String startTime,
            @JsonProperty("endTime") String endTime,
            @JsonProperty("channel") Integer channel) {
        RecordingConfig defaults = defaults();
        return new RecordingConfig(
                resourceCount == null ? defaults.resourceCount() : resourceCount,
                startTime == null ? defaults.startTime() : startTime,
                endTime == null ? defaults.endTime() : endTime,
                channel == null ? defaults.channel() : channel);
    }

    public RecordingConfig {
        if (resourceCount < 0 || resourceCount > 100) {
            throw new IllegalArgumentException("resourceCount must be in range 0..100");
        }
        startTime = normalizeTime(startTime, "startTime");
        endTime = normalizeTime(endTime, "endTime");
        if (channel < 1 || channel > 255) {
            throw new IllegalArgumentException("channel must be in range 1..255");
        }
    }

    public static RecordingConfig defaults() {
        return new RecordingConfig(DEFAULT_RESOURCE_COUNT, DEFAULT_START_TIME, DEFAULT_END_TIME, 1);
    }

    private static String normalizeTime(String value, String name) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (!(normalized.equals("NOW") || normalized.equals("NOW-2H")
                || normalized.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))) {
            throw new IllegalArgumentException(name + " must be HH:mm, NOW, or NOW-2H");
        }
        return normalized;
    }
}
