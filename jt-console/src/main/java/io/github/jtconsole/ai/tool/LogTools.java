package io.github.jtconsole.ai.tool;

import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.domain.DeviceLogPage;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.DeviceLogQueryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * AI 的只读报文日志查询，与「设备日志」页走同一个查询服务。
 *
 * <p>参数契约（字段名、格式、示例、默认值）**全部写在注解描述里**：Spring AI 由注解反射生成
 * schema 送给模型，写在别处的说明模型根本看不见。这条教训来自动作清单——当时清单只报动作名，
 * 模型只能猜字段，一个请求为此往返六轮，其中两轮走到用户点确认才失败。
 * {@code LogToolContractTest} 钉住这些描述文本，防回归。
 *
 * <p>租户隔离走 {@link ToolSession#scope()}，不经过模型：入参里因此没有、也不能有租户字段。
 */
@Component
public class LogTools {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ToolRunner runner;
    private final DeviceLogQueryService logs;

    public LogTools(ToolRunner runner, DeviceLogQueryService logs) {
        this.runner = runner;
        this.logs = logs;
    }

    @Tool(name = "query_device_logs", description = """
            查询一台设备的报文日志，返回按时间倒序的摘要行（时间、方向、消息 ID、概要）。
            覆盖三类记录：UP 设备上行报文、DOWN 平台下行指令、CONNECTION 上下线等连接事件，
            因此同一次查询就能看到「指令下发 → 终端应答」的完整往返。
            摘要行不含原始 hex 与解析正文；要看某一条的报文内容，用它返回的 id 调用
            get_device_log_detail。
            这是只读工具，不下发任何指令。查不到记录是有意义的结论：说明该时段平台没收到／没发出
            这类报文，请如实说明，不要说成查询失败。
            """)
    String queryDeviceLogs(
            @ToolParam(description = """
                    设备号（终端 SIM 号），必填。字符串，例如 "13800138000"。
                    不知道设备号时先用车辆查询工具按车牌查出来，不要猜。
                    """) String deviceId,
            @ToolParam(required = false, description = """
                    开始时间，可选。格式 yyyy-MM-ddTHH:mm:ss（例如 "2026-08-24T09:00:00"），
                    也接受只给日期的 "2026-08-24"（按当天 00:00:00 起算）。留空表示不限起点。
                    """) String start,
            @ToolParam(required = false, description = """
                    结束时间，可选。格式同 start（例如 "2026-08-24T10:00:00"）；
                    只给日期时按当天 23:59:59.999 结算。留空表示不限终点。
                    """) String end,
            @ToolParam(required = false, description = """
                    方向，可选。只接受三个值之一：UP（设备上行报文）、DOWN（平台下行指令）、
                    CONNECTION（上下线等连接事件）。留空表示三类都要。
                    """) String direction,
            @ToolParam(required = false, description = """
                    消息 ID，可选。十六进制或十进制皆可：0x0200 与 512 等价，都指位置汇报。
                    常用值：0x0200 位置汇报、0x0002 心跳、0x0100 终端注册、0x8801 拍照指令、
                    0x8104 查询终端参数。留空表示不按消息 ID 过滤。
                    """) String msgId,
            @ToolParam(required = false, description = """
                    关键字，可选。在「概要」与「解析后的报文正文」上做包含匹配（例如 "speedKph"、
                    "拍照"）。注意：不搜原始 hex，用十六进制字节串当关键字搜不到东西。
                    """) String keyword,
            @ToolParam(required = false, description = """
                    返回条数上限，可选。整数，默认 50，最大 200。超过 200 按 200 处理。
                    """) Integer limit,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "query_device_logs", "查询设备日志 " + deviceId,
                () -> query(deviceId, start, end, direction, msgId, keyword, limit, session));
    }

    @Tool(name = "get_device_log_detail", description = """
            按 id 取单条报文日志的完整内容：原始帧十六进制与解析后的报文正文。
            id 来自 query_device_logs 返回的每一行，不要自己编造。
            两类记录天生没有原始 hex：CONNECTION 连接事件（本来就不是报文）、
            以及含一次性 FTP 口令的 0x9206 文件上传指令（口令已在网关侧脱敏）。
            decodeError 为 true 时解析正文为空，只有原始字节可信。
            """)
    String getDeviceLogDetail(
            @ToolParam(description = """
                    日志记录 id，必填。整数，取自 query_device_logs 返回行里的 id 字段，例如 1287。
                    """) Long id,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "get_device_log_detail", "查看报文日志 #" + id,
                () -> detail(id, session));
    }

    Map<String, Object> query(
            String deviceId, String start, String end, String direction, String msgId,
            String keyword, Integer limit, ToolSession session) {
        if (deviceId == null || deviceId.isBlank()) {
            return ToolResults.error("缺少参数 deviceId（设备号，例如 \"13800138000\"）。"
                    + "不知道设备号就先用车辆查询工具按车牌查出来。");
        }
        int bounded = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        DeviceLogPage page;
        try {
            page = logs.query(deviceId, start, end, direction, msgId, keyword, 1, bounded,
                    session.scope());
        } catch (IamException notVisible) {
            if (!"4004".equals(notVisible.code())) {
                throw notVisible;
            }
            // 越权与不存在给同一个回答，避免这个工具变成跨租户设备的探测器。
            return emptyResult(deviceId);
        } catch (IllegalArgumentException invalid) {
            // 参数错误必须指出字段与正确格式，模型才能在同一轮里改对重试。
            return ToolResults.error(invalid.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId.trim());
        result.putAll(ToolResults.page("logs", summaries(page.items()), bounded, page.total()));
        if (page.items().isEmpty()) {
            result.put("note", "该设备在这个条件下没有日志：说明平台侧没收到／没发出这类报文，"
                    + "不是查询失败。");
        }
        return result;
    }

    Map<String, Object> detail(Long id, ToolSession session) {
        if (id == null) {
            return ToolResults.error("缺少参数 id（日志记录 id，整数，取自 query_device_logs 的返回行）。");
        }
        return logs.findById(id, session.scope())
                .<Map<String, Object>>map(LogTools::fullRow)
                .orElseGet(() -> ToolResults.error(
                        "没有 id=" + id + " 的日志记录，或它不在你的数据范围内。"
                                + "请先用 query_device_logs 拿到有效的 id。"));
    }

    /** 摘要行不带 hex 与解析正文——一条 0x0200 的解析 JSON 有两千多字符，几十条就能撑爆上下文。 */
    private static List<Map<String, Object>> summaries(List<DeviceLog> logs) {
        return logs.stream().map(log -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", log.id());
            row.put("logTime", log.logTime());
            row.put("direction", log.direction());
            row.put("msgId", log.msgIdHex());
            row.put("serialNo", log.serialNo());
            row.put("summary", log.summary());
            if (log.decodeError()) {
                row.put("decodeError", true);
            }
            if (log.truncated()) {
                row.put("truncated", true);
            }
            return row;
        }).toList();
    }

    private static Map<String, Object> fullRow(DeviceLog log) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", log.id());
        row.put("deviceId", log.deviceId());
        row.put("logTime", log.logTime());
        row.put("direction", log.direction());
        row.put("msgId", log.msgIdHex());
        row.put("serialNo", log.serialNo());
        row.put("summary", log.summary());
        row.put("rawHex", log.rawHex());
        row.put("parsedJson", log.parsedJson());
        row.put("decodeError", log.decodeError());
        row.put("truncated", log.truncated());
        return row;
    }

    private static Map<String, Object> emptyResult(String deviceId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId", deviceId.trim());
        result.put("logs", List.of());
        result.put("total", 0);
        result.put("note", "查不到这台设备，或它不在你的数据范围内。");
        return result;
    }
}
