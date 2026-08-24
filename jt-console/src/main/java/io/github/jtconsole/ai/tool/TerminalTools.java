package io.github.jtconsole.ai.tool;

import io.github.jtconsole.domain.TerminalPage;
import io.github.jtconsole.domain.TerminalSummary;
import io.github.jtconsole.operations.TerminalQueryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * AI 的只读终端清单查询，与「终端管理」页走同一个查询服务。
 *
 * <p>可见范围因此天然一致：租户会话下的 AI 同样看不到未建档终端，
 * 隔离不在工具层重写第二遍。租户信息走 {@link ToolSession#scope()}，不经过模型。
 *
 * <p>参数契约全部写在注解描述里——写在别处模型看不见，这条已经栽过两次
 * （动作清单、报文日志工具），{@code TerminalToolContractTest} 钉住它。
 */
@Component
public class TerminalTools {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ToolRunner runner;
    private final TerminalQueryService terminals;

    public TerminalTools(ToolRunner runner, TerminalQueryService terminals) {
        this.runner = runner;
        this.terminals = terminals;
    }

    @Tool(name = "query_terminals", description = """
            查询「连接过网关的终端」台账，回答「现在有哪些终端在连」「哪些还没建档」
            「某个终端 ID 是哪台设备」这类问题。
            台账记录的是终端**自报**的身份（终端 ID、制造商、型号、车牌），自报值未经核实，
            转述时要说明这一点，不要当成车辆档案里的确认信息。
            终端在下一次注册或鉴权时入册，所以长期保持长连接、尚未重连过的终端可能还不在册。
            **未建档终端只对平台管理员可见**：租户账号查不到它们，这时结果为空是正常的权限边界，
            必须如实说明「你的账号看不到未建档终端」，不要说成查询失败。
            这是只读工具，不能建档——建档要人确认车牌，请引导用户去「终端管理」页操作。
            """)
    String queryTerminals(
            @ToolParam(required = false, description = """
                    关键字，可选。在终端手机号、终端 ID、自报车牌、终端型号、档案车牌上做包含匹配。
                    例如 "1380000"（终端 ID）、"京A"（车牌片段）、"SIMULATOR"（型号）。
                    留空表示不按关键字过滤。
                    """) String keyword,
            @ToolParam(required = false, description = """
                    建档状态，可选。true 只看已建立车辆档案的，false 只看还没建档的，
                    留空表示两者都要。问「哪些终端还没建档」时传 false。
                    """) Boolean archived,
            @ToolParam(required = false, description = """
                    在线状态，可选。true 只看当前在线的，false 只看离线的，留空表示都要。
                    注意在线状态与「最近注册/鉴权时间」是两回事：长连不断的终端在线，
                    但它的最近注册时间可能是很多天以前。
                    """) Boolean online,
            @ToolParam(required = false, description = """
                    返回条数上限，可选。整数，默认 20，最大 100。超过 100 按 100 处理。
                    """) Integer limit,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "query_terminals", "查询终端清单",
                () -> query(keyword, archived, online, limit, session));
    }

    Map<String, Object> query(
            String keyword, Boolean archived, Boolean online, Integer limit, ToolSession session) {
        int bounded = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        TerminalPage page = terminals.search(
                keyword, archived, online, null, null, 1, bounded, session.scope());

        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(ToolResults.page("terminals", summaries(page.items()), bounded, page.total()));
        if (page.items().isEmpty()) {
            // 空结果在租户会话下是权限边界而不是故障，说清楚模型才不会报成「查询失败」。
            result.put("note", Boolean.FALSE.equals(archived) && !session.scope().isPlatform()
                    ? "没有结果：未建档终端只有平台管理员看得到，当前账号是租户范围。"
                    : "没有匹配的终端。终端在下一次注册或鉴权时才入册，"
                            + "长期长连未重连的终端可能还不在册。");
        }
        return result;
    }

    /** 摘要行控 token：清单是「有哪些」的问题，省市域 ID、协议版本这些细节不进模型上下文。 */
    private static List<Map<String, Object>> summaries(List<TerminalSummary> terminals) {
        return terminals.stream().map(terminal -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", terminal.deviceId());
            row.put("terminalId", terminal.terminalId());
            row.put("archived", terminal.archived());
            row.put("plateNo", terminal.plateNo());
            row.put("reportedPlate", terminal.reportedPlate());
            row.put("deviceModel", terminal.deviceModel());
            row.put("online", terminal.online());
            row.put("lastRegisteredAt", terminal.lastSeenAt());
            return row;
        }).toList();
    }
}
