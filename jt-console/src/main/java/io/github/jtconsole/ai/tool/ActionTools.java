package io.github.jtconsole.ai.tool;

import io.github.jtconsole.ai.action.ActionProposal;
import io.github.jtconsole.ai.action.ActionProposalService;
import io.github.jtconsole.ai.action.ActionType;
import io.github.jtconsole.ai.agent.AiEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 唯一一个会导致数据变更的工具——而它自己也不改任何数据。
 *
 * <p>它做的全部事情是：校验、推一个事件、告诉模型「已交给用户」。真正的写入由前端用**用户自己的
 * 令牌**调既有 REST 接口完成。因此服务端 AI 进程里不存在任何写业务数据的代码路径：提示注入最坏
 * 也只能让模型**建议**一个动作，而那个动作还要过用户这一关、过既有接口的权限与业务校验。
 */
@Component
public class ActionTools {

    private final ToolRunner runner;
    private final ActionProposalService proposals;

    public ActionTools(ToolRunner runner, ActionProposalService proposals) {
        this.runner = runner;
        this.proposals = proposals;
    }

    @Tool(name = "propose_action",
            description = """
                    提议一个需要改动平台数据的操作，例如建档车辆、创建车队或围栏、调整成员、处置告警。
                    你不能直接执行任何操作，只能提议——平台会把提议交给用户，由用户决定是否执行，
                    因此你无法得知最终结果，不要假称操作已完成。
                    一次只提议一个动作。params 里要填的是提交给平台的字段，尽量从用户的话里补全；
                    确实缺少必填信息时先向用户问清楚，不要自行编造车牌、设备号这类关键字段。
                    引用已有对象的动作（处置告警、修改或删除车辆、下发消息）必须先用查询工具拿到
                    真实的告警编号或设备号，不要凭印象填。""")
    String proposeAction(
            @ToolParam(description = "动作类型，必须是平台开放的类型之一") String type,
            @ToolParam(description = "给用户看的一句话说明，写清将要做什么") String title,
            @ToolParam(description = "提议理由，会展示在确认卡片上", required = false) String reason,
            @ToolParam(description = "提交给平台的字段，键名与平台接口一致") Map<String, Object> params,
            ToolContext context) {
        ToolSession session = ToolSession.from(context);
        return runner.run(session, "propose_action", "准备操作：" + title, () -> {
            ActionProposalService.Outcome outcome = proposals.propose(
                    session, type, title, reason, params, session.confirmationPolicy());
            if (!outcome.accepted()) {
                return ToolResults.error(outcome.message());
            }
            ActionProposal proposal = outcome.proposal();
            session.events().emit(new AiEvent(AiEvent.Kind.ACTION, proposal.asEventData()));

            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("status", "proposed");
            reply.put("proposalId", proposal.proposalId());
            reply.put("note", proposal.requiresConfirmation()
                    ? "已把操作交给用户确认。你无法得知用户是否确认，也无法得知执行结果，"
                            + "请不要声称操作已完成。"
                    : "该动作已配置为免确认，平台会直接执行。你仍然无法得知执行结果，"
                            + "请不要声称操作已成功。");
            return reply;
        });
    }

    /**
     * 当前发起人可用的动作清单，拼进系统提示词。
     *
     * <p>把清单放进提示词而不是让模型盲猜类型名：模型不知道有哪些合法值时会自己发明一个，
     * 然后拿到一句「不支持的动作类型」，白费一轮。
     */
    public String describeAvailableActions(List<ActionType> available) {
        if (available.isEmpty()) {
            return "当前用户没有任何可执行的操作权限，不要提议任何动作，遇到操作请求时请说明其权限不足。";
        }
        return "当前用户可提议的动作类型：\n"
                + available.stream()
                        .map(type -> "- %s：%s%s".formatted(
                                type.wireName(), type.label(),
                                type.alwaysConfirm() ? "（不可逆，必定需要用户确认）" : ""))
                        .collect(Collectors.joining("\n"));
    }
}
