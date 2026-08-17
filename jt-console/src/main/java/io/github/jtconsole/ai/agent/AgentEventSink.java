package io.github.jtconsole.ai.agent;

/**
 * 对话过程中向前端推送事件的通道。
 *
 * <p>做成接口是为了让工具与 Agent 逻辑不依赖 Servlet：测试用收集实现即可断言事件序列，
 * 不必起一个真的 SSE 连接。
 */
public interface AgentEventSink {

    void emit(AiEvent event);

    /** 客户端是否已断开。工具与循环据此尽早退出，不必把剩下的活干完。 */
    boolean cancelled();

    /** 什么都不做的实现，用于日报这类没有前端在等的场景。 */
    static AgentEventSink noop() {
        return new AgentEventSink() {
            @Override
            public void emit(AiEvent event) {
                // 无人订阅
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }
}
