package io.github.jtconsole.ai.tool;

/**
 * 一次对话里的工具调用轮数配额。
 *
 * <p><b>存在的理由</b>：工具循环是模型自己驱动的——它看到工具结果后可以再调一次，如此往复。
 * 正常情况下两三轮就收敛，但模型完全可能陷进「查不到 → 换个参数再查 → 还是查不到」的循环里，
 * 而这中间没有任何人在看。每一轮都要跑一次完整的模型推理，烧 token、占线程池名额、
 * 让用户对着转圈等——{@code concurrent-chats} 是 4，一个转不出来的对话就占掉四分之一。
 *
 * <p><b>为什么是「轮」不是「次」</b>：模型一轮可以并行发起多个工具调用（先查车辆再查轨迹），
 * 那是一次推理的结果，算一轮。按次计数会让「一轮查三样」的正常行为过早触顶。
 *
 * <p><b>刻意可变</b>，与 {@link io.github.jtconsole.ai.view.ViewBudget} 同理：一轮对话里要跨多次
 * 框架回调累计计数。它作为显式分量挂在 {@link ToolSession} 上，不偷偷藏一个静态计数器——
 * 那样在并发对话下会互相串扰。
 */
public final class ToolRoundBudget {

    private final int limit;
    private int used;

    public ToolRoundBudget(int limit) {
        // 0 或负数会让第一轮就被拒，等于关掉工具能力。这不是配置项想表达的意思，兜底为 1。
        this.limit = Math.max(1, limit);
    }

    /**
     * 占用一轮。
     *
     * @return 还在上限内时为 true；已经超出时为 false，此时调用方必须终止循环
     */
    public synchronized boolean tryConsume() {
        if (used >= limit) {
            return false;
        }
        used++;
        return true;
    }

    public int limit() {
        return limit;
    }

    public synchronized int used() {
        return used;
    }
}
