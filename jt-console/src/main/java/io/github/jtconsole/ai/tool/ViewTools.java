package io.github.jtconsole.ai.tool;

import io.github.jtconsole.ai.agent.AiEvent;
import io.github.jtconsole.ai.view.ChartSpec;
import io.github.jtconsole.ai.view.ViewProposal;
import io.github.jtconsole.ai.view.ViewProposalService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 把地图、图表这类内容放进对话里。
 *
 * <p>与 {@code ActionTools} 走同一条路：校验、推一个事件、告诉模型已展示。区别在于视图是**只读**的，
 * 没有确认、没有执行、没有结果回灌。
 *
 * <p><b>每种视图一个独立工具，而不是一个带类型参数的通用工具。</b>动作提议能用通用签名，是因为
 * 18 个动作的参数高度同构，都是扁平字段包；而各类视图的参数完全不同构（位置只要设备号、轨迹要时间窗、
 * 视频要通道号、图表要嵌套的系列数组）。框架会**从方法签名生成结构化的参数说明**——四个独立工具给
 * 模型四份结构化说明，一个通用工具只给一个自由字典加一段自然语言，而嵌套结构上模型对着自然语言
 * 基本写不对。
 */
@Component
public class ViewTools {

    private final ToolRunner runner;
    private final ViewProposalService proposals;

    public ViewTools(ToolRunner runner, ViewProposalService proposals) {
        this.runner = runner;
        this.proposals = proposals;
    }

    @Tool(name = "show_live_map",
            description = """
                    在对话里内嵌一张实时位置小地图。
                    用户问「在哪」「现在什么位置」「跑到哪了」时，除了用文字说出地址，再调用本工具出图。
                    留空 deviceId 表示展示当前全部在线车辆。
                    只问「有多少台在线」这类数字时不要调用。""")
    String showLiveMap(
            @ToolParam(description = "设备号；留空则展示全部在线车辆", required = false) String deviceId,
            @ToolParam(description = "卡片标题，一句话说明这是什么", required = false) String title,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        Map<String, Object> params = new LinkedHashMap<>();
        if (deviceId != null && !deviceId.isBlank()) {
            params.put("deviceId", deviceId.trim());
        }
        return show(session, "live_map", title, params);
    }

    @Tool(name = "show_track_map",
            description = """
                    在对话里内嵌一张行驶轨迹地图。
                    用户问「去过哪」「走的什么路线」「给我看看轨迹」时用它。
                    时间跨度不能超过 24 小时；只问里程数字时不要调用。""")
    String showTrackMap(
            @ToolParam(description = "设备号") String deviceId,
            @ToolParam(description = "开始时间 yyyy-MM-ddTHH:mm:ss") String start,
            @ToolParam(description = "结束时间，格式同上") String end,
            @ToolParam(description = "卡片标题", required = false) String title,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("deviceId", deviceId == null ? null : deviceId.trim());
        params.put("start", start == null ? null : start.trim());
        params.put("end", end == null ? null : end.trim());
        return show(session, "track_map", title, params);
    }

    @Tool(name = "show_live_video",
            description = """
                    在对话里放一张实时视频卡片，用户点击后才真正开始拉流。
                    只有用户明确要求看视频、看画面时才调用——**开流会向车辆下发指令**，不要主动提议。""")
    String showLiveVideo(
            @ToolParam(description = "设备号") String deviceId,
            @ToolParam(description = "摄像头通道号，默认 1", required = false) Integer channel,
            @ToolParam(description = "卡片标题", required = false) String title,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("deviceId", deviceId == null ? null : deviceId.trim());
        if (channel != null) {
            params.put("channel", channel);
        }
        return show(session, "live_video", title, params);
    }

    @Tool(name = "show_chart",
            description = """
                    把你已经查到的数据画成图表。
                    **仅用于需要跨工具聚合、对比、筛选后才能得到的图**（多台车对比、多维度占比）；
                    单纯的逐日趋势请改用 get_daily_stats 的 showChart 参数——那样数据由平台直接组织，更准。
                    数值必须来自你本轮真实查到的结果，绝不允许估算或补全。
                    缺测的位置用 null，不要填 0——null 画成断线，0 会被读成真的是 0。
                    source 必填，写清数据来自哪次查询，它会显示在图下方供用户核对。""")
    String showChart(ChartSpec spec, ToolContext context) {
        ToolSession session = ToolSession.from(context);
        String brief = spec == null || spec.title() == null || spec.title().isBlank()
                ? "准备展示图表" : "展示图表：" + spec.title();
        return runner.run(session, "show_chart", brief, () -> {
            ViewProposalService.Outcome outcome = proposals.propose(session, spec);
            if (!outcome.accepted()) {
                return ToolResults.error(outcome.message());
            }
            session.events().emit(
                    new AiEvent(AiEvent.Kind.VIEW, outcome.proposal().asEventData()));
            return shownReply();
        });
    }

    /**
     * 校验通过就推事件，否则把原因回给模型。
     *
     * <p>回给模型的话刻意很短，而且**不回传 viewId、不回显参数**——回显会让同一份内容在上下文里
     * 存两遍，而给了编号它就会在回答里复述，复述几次之后就学会了凭空编一个说「已展示」。
     */
    private String show(
            ToolSession session, String type, String title, Map<String, Object> params) {
        String brief = title == null || title.isBlank() ? "准备展示视图" : "展示：" + title;
        return runner.run(session, "show_" + type, brief, () -> {
            ViewProposalService.Outcome outcome = proposals.propose(session, type, title, params);
            if (!outcome.accepted()) {
                return ToolResults.error(outcome.message());
            }
            ViewProposal proposal = outcome.proposal();
            session.events().emit(new AiEvent(AiEvent.Kind.VIEW, proposal.asEventData()));

            return shownReply();
        });
    }

    /**
     * 回给模型的话。
     *
     * <p>刻意很短，而且**不回传 viewId、不回显参数与数值**：回显会让同一份数据在上下文里存两遍；
     * 而给了编号它就会在回答里复述，复述几次之后就学会了凭空编一个说「已展示」。
     *
     * <p><b>必须说清方位</b>。页面把一条回复固定渲染成「文字 → 视图 → 动作卡」，视图在文字
     * **下方**；而模型自己的视角是「先调工具、后说话」，不告诉它方位它就会脑补成「上方」，
     * 于是线上出现过「轨迹地图已在上方展示」而图其实在下面。动作卡那边早就在系统提示里
     * 写明了「紧跟在你这条消息下方」，视图这边漏了同样的一句。
     *
     * <p>末句同样重要——不写的话模型会把图上的 31 天数值再用文字念一遍，图就白做了。
     */
    private static Map<String, Object> shownReply() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("status", "shown");
        reply.put("note", "视图已展示在本次对话里、紧跟在你这条消息下方。"
                + "指方位时只能说「下方」，不要说「上方」。"
                + "请继续用文字给出结论（最高/最低/趋势），"
                + "但不要逐条复述图上已经能看到的数值。");
        return reply;
    }
}
