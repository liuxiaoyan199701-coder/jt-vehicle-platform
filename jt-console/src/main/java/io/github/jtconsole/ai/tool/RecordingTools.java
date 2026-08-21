package io.github.jtconsole.ai.tool;

import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.repository.TimeBounds;
import io.github.jtconsole.web.RecordingProxyController;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** AI 的只读录像查询。复用网页检索服务，确保数据范围、在线判定和两类来源口径完全一致。 */
@Component
public class RecordingTools {
    private static final Duration MAX_RANGE = Duration.ofDays(7);
    private static final int SUMMARY_LIMIT = 10;

    private final ToolRunner runner;
    private final RecordingProxyController recordings;

    public RecordingTools(ToolRunner runner, RecordingProxyController recordings) {
        this.runner = runner;
        this.recordings = recordings;
    }

    @Tool(name = "query_recordings",
            description = """
                    查询一台车某时段是否有录像，分别返回平台侧分片和设备侧 SD 卡资源的可用性与概要。
                    时间跨度最多 7 天；设备离线时设备侧会明确不可用，平台侧仍照常查询。
                    这是只读查询工具，不能替用户开流或播放录像；用户要看画面时，必须引导去「录像回放」页。
                    """)
    String queryRecordings(
            @ToolParam(description = "设备号") String deviceId,
            @ToolParam(description = "开始时间，yyyy-MM-ddTHH:mm:ss 或带时区 ISO 时间") String start,
            @ToolParam(description = "结束时间，格式同开始时间") String end,
            @ToolParam(description = "逻辑通道号，默认 1", required = false) Integer channel,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "query_recordings", "查询录像 " + deviceId,
                () -> query(deviceId, start, end, channel, session));
    }

    Map<String, Object> query(
            String deviceId, String start, String end, Integer channel, ToolSession session) {
        if (deviceId == null || deviceId.isBlank()) {
            return ToolResults.error("缺少设备号，请先用车辆查询工具确认是哪一台车。");
        }
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            return ToolResults.error("录像查询必须给出开始和结束时间，时间跨度最多 7 天。");
        }
        Instant from = TimeBounds.instant(start);
        Instant to = TimeBounds.upperInstant(end);
        if (!to.isAfter(from)) {
            return ToolResults.error("结束时间必须晚于开始时间。");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            return ToolResults.error("录像查询时间跨度不能超过 7 天。");
        }

        try {
            var response = recordings.search(
                    deviceId.trim(), channel == null ? 1 : channel, "main", from, to, session.scope());
            var result = response.data();
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("deviceId", deviceId.trim());
            answer.put("startTime", from.toString());
            answer.put("endTime", to.toString());
            answer.put("platform", sourceSummary(
                    result.platform().available(), result.platform().reason(),
                    result.platform().segments(), "segments"));
            answer.put("device", sourceSummary(
                    result.device().available(), result.device().reason(),
                    result.device().resources(), "resources"));
            answer.put("playback", "本工具不能开流；要看录像请前往录像回放页。");
            return answer;
        } catch (IamException invisible) {
            // 不向模型区分“不存在”和“越权”，也不暴露任一来源是否实际有数据。
            return Map.of(
                    "deviceId", deviceId.trim(),
                    "platform", sourceSummary(false, null, List.of(), "segments"),
                    "device", sourceSummary(false, null, List.of(), "resources"),
                    "note", "没有可查询的录像结果。",
                    "playback", "本工具不能开流；要看录像请前往录像回放页。");
        }
    }

    private static Map<String, Object> sourceSummary(
            boolean available, String reason, List<?> items, String itemName) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("available", available);
        if (reason != null && !reason.isBlank()) {
            source.put("reason", reason);
        }
        source.put("count", items.size());
        source.put(itemName, items.size() > SUMMARY_LIMIT ? items.subList(0, SUMMARY_LIMIT) : items);
        if (items.size() > SUMMARY_LIMIT) {
            source.put("truncated", true);
        }
        return source;
    }
}
