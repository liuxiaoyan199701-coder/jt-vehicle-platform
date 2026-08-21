package io.github.jtconsole.ai.tool;

import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.DeviceDiagnosticsService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 只读的设备体检工具；连接、时钟、定位、抓拍、开流五维各自降级。 */
@Component
public class DiagnosticsTools {
    private final ToolRunner runner;
    private final DeviceDiagnosticsService diagnostics;

    public DiagnosticsTools(ToolRunner runner, DeviceDiagnosticsService diagnostics) {
        this.runner = runner;
        this.diagnostics = diagnostics;
    }

    @Tool(name = "diagnose_device", description = """
            对一台设备做一次体检，一次返回连接、时钟偏差、定位质量、抓拍跟进、开流跟进五个维度。
            各维度独立查询、独立降级，某一维不可用不影响其它维度；结果中的 available=false 必须如实告诉用户。
            时间跨度最多 7 天。连接维度若平台侧查无连接记录，这是重要诊断结论：说明终端未到达平台，
            排查方向在 SIM 卡、蜂窝网络、终端 IP/端口配置，不要说成无法诊断。
            时钟偏差约 8 小时要明确提示「疑似把北京时间当 UTC 上报」，不要把它误判为普通网络延迟。
            抓拍维度会识别「指令已下发但图片未到达平台」；开流维度只能确认平台已下发，媒体到达情况需查媒体节点。
            """)
    String diagnoseDevice(
            @ToolParam(description = "设备号（终端 SIM 号）") String deviceId,
            @ToolParam(description = "开始时间，格式 yyyy-MM-ddTHH:mm:ss；留空表示最近 7 天", required = false)
            String start,
            @ToolParam(description = "结束时间，格式同上；留空表示现在", required = false) String end,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "diagnose_device", "体检设备 " + deviceId, () -> {
            if (deviceId == null || deviceId.isBlank()) {
                return ToolResults.error("缺少设备号，请先确认是哪一台车。");
            }
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
            String from = blank(start) ? now.minusDays(7).toString() : start.trim();
            String to = blank(end) ? now.toString() : end.trim();
            if (span(from, to).compareTo(Duration.ofDays(7)) > 0) {
                return ToolResults.error("体检时间跨度不能超过 7 天，请缩小范围。");
            }
            // 预先执行范围判定：越权必须返回空诊断，且不触发任何维度的旁路查询。
            String device = deviceId.trim();
            try {
                diagnostics.authorize(device, from, to, session.scope());
            } catch (IamException notVisible) {
                if (!"4004".equals(notVisible.code())) {
                    throw notVisible;
                }
                return emptyDiagnosis(device);
            }
            return diagnostics.diagnose(device, from, to, session.scope());
        });
    }

    private static Map<String, Object> emptyDiagnosis(String deviceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId);
        result.put("connection", Map.of("available", false, "summary", Map.of(), "timeline", List.of()));
        for (String dimension : List.of("clock", "positioning", "photoFollowUp", "streamFollowUp")) {
            result.put(dimension, Map.of("available", false));
        }
        return result;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Duration span(String from, String to) {
        try {
            OffsetDateTime lower = parse(from);
            OffsetDateTime upper = parse(to);
            if (upper.isBefore(lower)) {
                throw new IllegalArgumentException("结束时间不能早于开始时间");
            }
            return Duration.between(lower, upper);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("时间格式无法识别，请使用 yyyy-MM-ddTHH:mm:ss");
        }
    }

    private static OffsetDateTime parse(String value) {
        String normalized = value.trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeParseException noOffset) {
            return OffsetDateTime.parse(normalized + "+08:00");
        }
    }
}
