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
                    提议一个改动平台数据的操作：建档车辆、创建车队或围栏、调整成员、处置告警等。
                    平台会把提议交给用户确认，因此你无法得知结果，不要说「已完成」。
                    需要连做几件事时，逐个调用本工具即可，不必等用户逐条批准。
                    params 用下面列出的字段名原样填写。用户没提到的非关键字段自己拟合理值，
                    不要为此回头追问；只有设备号、车牌、告警编号这类指向已有对象的标识不能猜。""")
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
        return describeAvailableActions(available, true);
    }

    /**
     * @param platform 平台管理员。非平台用户看不到 tenantId 字段——后端本来就按登录态取，
     *                 让它出现在清单里只会诱使模型去填，然后填成用户名之类的东西
     */
    public String describeAvailableActions(List<ActionType> available, boolean platform) {
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
