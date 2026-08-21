package io.github.jtconsole.ai.tool;

import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.WaybillRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** AI 的只读电子运单查询，只返回时间与安全预览，不把整段 base64 塞进模型上下文。 */
@Component
public class WaybillTools {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final ToolRunner runner;
    private final VehicleService vehicles;
    private final WaybillRepository waybills;

    public WaybillTools(
            ToolRunner runner, VehicleService vehicles, WaybillRepository waybills) {
        this.runner = runner;
        this.vehicles = vehicles;
        this.waybills = waybills;
    }

    @Tool(name = "query_waybills",
            description = "查询一台车最近上报的电子运单，返回上报时间、原文长度和 UTF-8 预览。"
                    + "运单格式可能是厂商私有二进制，预览不可用时应如实说明，不要猜测内容。")
    String queryWaybills(
            @ToolParam(description = "设备号") String deviceId,
            @ToolParam(description = "返回条数，默认 10，最多 50", required = false) Integer limit,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "query_waybills", "查询电子运单 " + deviceId,
                () -> query(deviceId, limit, session));
    }

    Map<String, Object> query(String deviceId, Integer limit, ToolSession session) {
        if (deviceId == null || deviceId.isBlank()) {
            return ToolResults.error("缺少设备号，请先用车辆查询工具确认是哪一台车。");
        }
        String requested = deviceId.trim();
        int size = Math.min(MAX_LIMIT, Math.max(1, limit == null ? DEFAULT_LIMIT : limit));
        try {
            String canonicalId = vehicles.requireVisibleDevice(requested, session.scope());
            var page = waybills.findByDevice(canonicalId, 1, size, session.scope());
            List<Map<String, Object>> rows = page.items().stream().map(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("reportedAt", item.reportedAt());
                row.put("rawLength", item.rawLength());
                row.put("utf8", item.utf8());
                row.put("preview", item.preview());
                return row;
            }).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", canonicalId);
            result.put("waybills", rows);
            result.put("total", page.total());
            if (rows.isEmpty()) {
                result.put("note", "该设备暂无电子运单上报记录。");
            }
            return result;
        } catch (IamException invisible) {
            // 与其它查询工具一致：越权设备不向模型泄露存在性。
            return Map.of("deviceId", requested, "waybills", List.of(), "total", 0,
                    "note", "没有可查询的电子运单记录。");
        }
    }
}
