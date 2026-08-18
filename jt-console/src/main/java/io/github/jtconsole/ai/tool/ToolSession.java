package io.github.jtconsole.ai.tool;

import io.github.jtconsole.ai.action.ConfirmationPolicy;
import io.github.jtconsole.ai.agent.AgentEventSink;
import io.github.jtconsole.ai.view.ViewBudget;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 一次对话的工具执行上下文。
 *
 * <p>经 Spring AI 的 {@link ToolContext} 传递，**不经过模型**——这是本变更最重要的安全不变量：
 * 模型输出天生不可信，但只要数据范围不来自模型，越权就不可能发生。工具的入参 schema 里
 * 因此不存在租户、部门这类字段。
 */
public record ToolSession(
        AuthorizedPrincipal principal,
        DataScope scope,
        ZoneId zone,
        AgentEventSink events,
        ConfirmationPolicy confirmationPolicy,
        /**
         * 本轮的视图配额。刻意是可变对象——一轮对话内要跨多次工具调用累计计数，
         * 而这个 record 的其余分量都是不可变的，所以在这里点明。
         */
        ViewBudget viewBudget,
        /**
         * 本轮的工具调用轮数配额。同样是可变对象，理由同上。
         *
         * <p>由 {@code BoundedToolCallingManager} 在每轮工具执行前扣减——那是框架里唯一
         * 「每轮恰好经过一次」的位置。工具自身不碰它。
         */
        ToolRoundBudget roundBudget) {

    private static final String KEY = "jt.tool.session";

    /** 供框架侧从原始上下文映射中取回会话。与 {@link #from(ToolContext)} 同一个键。 */
    public static ToolSession fromContextMap(Map<String, Object> context) {
        Object value = context == null ? null : context.get(KEY);
        return value instanceof ToolSession session ? session : null;
    }

    public Map<String, Object> asContext() {
        return Map.of(KEY, this);
    }

    /**
     * 从框架传来的上下文中取回本会话。
     *
     * @throws IllegalStateException 上下文缺失时。这不是可以降级处理的情况——拿不到数据范围就
     *                               只能失败，绝不能退化成「不加范围条件地查」
     */
    public static ToolSession from(ToolContext context) {
        Object value = context == null ? null : context.getContext().get(KEY);
        if (value instanceof ToolSession session) {
            return session;
        }
        throw new IllegalStateException("工具上下文缺少会话信息，拒绝在没有数据范围的情况下查询");
    }
}
