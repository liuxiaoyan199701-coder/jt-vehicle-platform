package io.github.jtconsole.ai.agent;

import io.github.jtconsole.ai.tool.ToolRoundBudget;
import io.github.jtconsole.ai.tool.ToolSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 给工具调用循环加一个轮数上限。
 *
 * <p><b>为什么必须有</b>：工具循环由模型自己驱动——它看到结果后可以再调一次，如此往复，
 * 框架不设上限。规格明确要求「工具调用轮数 MUST 有上限」，而
 * {@code ConsoleProperties.Ai.maxToolRounds} 长期配了值却没有任何调用方，
 * 规格与实现就此分叉。真实风险很具体：模型陷进「查不到 → 换参数再查 → 还是查不到」的循环，
 * 每轮都要跑一次完整推理，烧 token、占住 {@code concurrent-chats} 仅有的四个名额之一，
 * 用户对着转圈等到超时。
 *
 * <p><b>为什么拦在这里</b>：{@link ToolCallingManager#executeToolCalls} 是框架里唯一
 * 「每一轮工具执行恰好经过一次」的位置。工具自身（{@code ToolRunner}）拦不住——模型一轮可以
 * 并行发起多个调用，在那里计数会把「一轮查三样」这种正常行为算成三轮。
 *
 * <p><b>超限时为什么直接终止而不是回告模型</b>：回告「已达上限」的话，模型完全可能再调一次工具
 * 来「确认一下」，于是又一轮，再回告，循环并不会停。{@code returnDirect} 是唯一能保证收敛的出口。
 * 代价是用户看到的是一句固定文案而不是模型组织的回答——但那正是「已经不正常了」时该给的东西。
 */
public class BoundedToolCallingManager implements ToolCallingManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoundedToolCallingManager.class);

    private final ToolCallingManager delegate;
    private final int defaultLimit;

    public BoundedToolCallingManager(ToolCallingManager delegate, int defaultLimit) {
        this.delegate = delegate;
        this.defaultLimit = Math.max(1, defaultLimit);
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        ToolRoundBudget budget = budgetOf(prompt);
        if (budget != null && !budget.tryConsume()) {
            LOGGER.warn("工具调用轮数达到上限 {}，终止本轮循环", budget.limit());
            return exhausted(prompt, response, budget.limit());
        }
        return delegate.executeToolCalls(prompt, response);
    }

    /**
     * 从提示里取回本次对话的轮数配额。
     *
     * <p>取不到时返回 null 并放行：这条路径上还有非本平台发起的调用（例如框架自身的探测），
     * 拿不到配额就拦截会把它们一起挡掉。真正的对话入口一定带得上会话。
     */
    private static ToolRoundBudget budgetOf(Prompt prompt) {
        if (prompt == null || !(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            return null;
        }
        Map<String, Object> context = options.getToolContext();
        ToolSession session = ToolSession.fromContextMap(context);
        return session == null ? null : session.roundBudget();
    }

    /**
     * 构造一个终止用的结果。
     *
     * <p>必须给**每一个**待执行的工具调用都回一条响应：少一条的话，历史里会留下一个没有结果的
     * 工具调用，下一次带着这段历史请求模型时，上游会因为消息序列不合法而拒绝整个请求。
     */
    private static ToolExecutionResult exhausted(Prompt prompt, ChatResponse response, int limit) {
        String text = "我为这个问题查询了 " + limit + " 轮仍未得到结论，先停下来避免一直查下去。"
                + "请把问题说得更具体一些（比如指明车辆、时间范围），我再继续。";

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        if (response != null && response.getResult() != null
                && response.getResult().getOutput() != null
                && response.getResult().getOutput().getToolCalls() != null) {
            response.getResult().getOutput().getToolCalls().forEach(call ->
                    responses.add(new ToolResponseMessage.ToolResponse(
                            call.id(), call.name(), text)));
        }

        List<Message> history = new ArrayList<>(prompt.getInstructions());
        if (response != null && response.getResult() != null) {
            history.add(response.getResult().getOutput());
        }
        if (!responses.isEmpty()) {
            history.add(ToolResponseMessage.builder().responses(responses).build());
        }

        return DefaultToolExecutionResult.builder()
                .conversationHistory(history)
                // 直接返回而不是继续循环——这是唯一能保证收敛的出口。
                .returnDirect(true)
                .build();
    }
}
