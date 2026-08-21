package io.github.jtplatform.simulator.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** 电子运单正文及行程联动开关。正文按 UTF-8 编码后写入 0701。 */
public record WaybillConfig(boolean autoSendOnTripStart, String content) {
    public static final String JSON_TEMPLATE = "{\"运单号\":\"WB-2026-001\",\"货物\":\"电子设备\",\"起点\":\"上海\",\"终点\":\"杭州\"}";
    public static final String TEXT_TEMPLATE = "运单号：WB-2026-001\n货物：电子设备\n起点：上海\n终点：杭州";

    public WaybillConfig {
        content = Objects.requireNonNull(content, "content");
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static WaybillConfig fromJson(
            @JsonProperty("autoSendOnTripStart") Boolean autoSendOnTripStart,
            @JsonProperty("content") String content) {
        WaybillConfig defaults = defaults();
        return new WaybillConfig(
                autoSendOnTripStart != null && autoSendOnTripStart,
                content == null ? defaults.content() : content);
    }

    public static WaybillConfig defaults() {
        return new WaybillConfig(false, JSON_TEMPLATE);
    }
}
