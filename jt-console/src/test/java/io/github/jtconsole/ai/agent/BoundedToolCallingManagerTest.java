package io.github.jtconsole.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.ai.tool.ToolRoundBudget;
import io.github.jtconsole.ai.tool.ToolSession;
import io.github.jtconsole.ai.view.ViewBudget;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.support.TestPrincipals;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具调用轮数上限。
 *
 * <p><b>为什么这个测试值钱</b>：上限的作用是「让不该发生的事不发生」，而它失效时没有任何现象——
 * 正常对话两三轮就收敛，看不出上限有没有生效。规格要求「工具调用轮数 MUST 有上限」，而这个配置项
 * 曾经配了值却没有任何调用方，分叉了很久也没人发现，正是因为缺这类断言。
 */
class BoundedToolCallingManagerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 记录被放行了多少次的委托替身。 */
    private static final class CountingDelegate implements ToolCallingManager {
        int calls;

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
            calls++;
            return DefaultToolExecutionResult.builder()
                    .conversationHistory(prompt.getInstructions())
                    .build();
        }
    }

    private static Prompt promptWith(ToolRoundBudget budget) {
        AuthorizedPrincipal principal = TestPrincipals.tenantAdmin(7L, 42L);
        ToolSession session = new ToolSession(
                principal, principal.scope(), ZONE, AgentEventSink.noop(),
                ConfirmationPolicy.confirmEverything(), new ViewBudget(), budget);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(session.asContext())
                .build();
        return new Prompt(List.of(new UserMessage("这台车在哪")), options);
    }

    private static ChatResponse responseWith(AssistantMessage.ToolCall... calls) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(calls))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse responseWithToolCall() {
        return responseWith(new AssistantMessage.ToolCall("call-1", "function", "query_track", "{}"));
    }

    @Test
    void allowsRoundsUpToTheLimit() {
        CountingDelegate delegate = new CountingDelegate();
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 3);
        Prompt prompt = promptWith(new ToolRoundBudget(3));
        ChatResponse response = responseWithToolCall();

        for (int round = 0; round < 3; round++) {
            ToolExecutionResult result = manager.executeToolCalls(prompt, response);
            assertThat(result.returnDirect()).as("第 %d 轮不该被截断", round + 1).isFalse();
        }
        assertThat(delegate.calls).isEqualTo(3);
    }

    /**
     * 超出上限后必须**终止循环**，而不是回告一句「已达上限」继续转。
     *
     * <p>回告的话模型完全可能再调一次工具来「确认一下」，于是又一轮、再回告，循环并不会停。
     * {@code returnDirect} 是唯一能保证收敛的出口。
     */
    @Test
    void stopsTheLoopOnceTheLimitIsExceeded() {
        CountingDelegate delegate = new CountingDelegate();
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 2);
        Prompt prompt = promptWith(new ToolRoundBudget(2));
        ChatResponse response = responseWithToolCall();

        manager.executeToolCalls(prompt, response);
        manager.executeToolCalls(prompt, response);
        ToolExecutionResult stopped = manager.executeToolCalls(prompt, response);

        assertThat(stopped.returnDirect()).isTrue();
        assertThat(delegate.calls).as("超限的那一轮不该真的执行工具").isEqualTo(2);
    }

    /**
     * 截断时必须给每一个待执行的工具调用都回一条响应。
     *
     * <p>少一条的话历史里会留下一个没有结果的工具调用，下次带着这段历史请求模型时，
     * 上游会因为消息序列不合法而拒绝**整个请求**——那比超时更难查。
     */
    @Test
    void answersEveryPendingToolCallWhenTruncating() {
        BoundedToolCallingManager manager = new BoundedToolCallingManager(new CountingDelegate(), 1);
        Prompt prompt = promptWith(new ToolRoundBudget(1));
        ChatResponse twoCalls = responseWith(
                new AssistantMessage.ToolCall("a", "function", "query_track", "{}"),
                new AssistantMessage.ToolCall("b", "function", "query_photos", "{}"));

        manager.executeToolCalls(prompt, twoCalls);
        ToolExecutionResult stopped = manager.executeToolCalls(prompt, twoCalls);

        ToolResponseMessage responses = (ToolResponseMessage) stopped.conversationHistory().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(responses.getResponses()).hasSize(2);
        assertThat(responses.getResponses()).allSatisfy(
                r -> assertThat(r.responseData()).contains("停下来"));
    }

    /** 拿不到会话时放行：这条路径上还有非对话入口的调用，一律拦截会把它们也挡掉。 */
    @Test
    void passesThroughWhenThereIsNoSession() {
        CountingDelegate delegate = new CountingDelegate();
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 1);
        Prompt bare = new Prompt(List.of((Message) new UserMessage("hi")));

        manager.executeToolCalls(bare, responseWithToolCall());
        manager.executeToolCalls(bare, responseWithToolCall());

        assertThat(delegate.calls).isEqualTo(2);
    }
}
