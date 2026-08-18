package io.github.jtconsole.ai.view;

import java.util.HashSet;
import java.util.Set;

/**
 * 单轮对话的视图配额。
 *
 * <p>存在的理由：「把这 50 台车的位置都给我看看」是一句完全合理的话，但 50 张地图会把页面塞满，
 * 也会同时建 50 个地图实例。上限让模型收到明确回告并改为合并或分次，而不是让界面塌掉。
 *
 * <p><b>刻意是可变的</b>，因为一轮对话里要跨多次工具调用累计计数。它作为显式分量挂在
 * {@code ToolSession} 上——不把一个可变计数器偷偷塞进不可变记录里，那样下一个人读不出来
 * 「这个 record 其实有状态」。
 */
public final class ViewBudget {

    /** 一轮最多几个视图。拍的值：三四张卡片还能看，再多就该合并了。 */
    public static final int DEFAULT_LIMIT = 4;

    private final int limit;
    private int used;
    private final Set<String> seen = new HashSet<>();

    public ViewBudget() {
        this(DEFAULT_LIMIT);
    }

    public ViewBudget(int limit) {
        this.limit = limit;
    }

    /**
     * 占用一个名额。
     *
     * <p>只在**校验全部通过、即将推送事件时**调用——被拒绝的提议不该消耗配额，否则模型改对参数
     * 重试几次就把额度耗光了。
     *
     * @return 还有名额时为 true
     */
    public synchronized boolean tryConsume() {
        if (used >= limit) {
            return false;
        }
        used++;
        return true;
    }

    /**
     * 按签名占用名额：同一签名一轮只放行一次。
     *
     * <p><b>为什么需要</b>：线上实测中，用户问一句「看看昨天的轨迹」，模型把时间窗层层收窄调了
     * 四次查询（24 小时 → 12 → 6 → 3），每次都触发出图，于是对话里堆了四张同一段行程的嵌套地图。
     * 那是模型在探索，不是用户想要四张图。
     *
     * <p>签名取「类型 + 主对象」而不是全部参数：时间窗不同但对象相同的几张图，对用户来说就是
     * 同一张。真正不同的对象（另一台车）签名不同，照常放行。
     *
     * @return 首次出现且还有名额时为 true；重复出现时为 false 且**不消耗名额**
     */
    public synchronized boolean tryConsume(String signature) {
        if (!seen.add(signature)) {
            return false;
        }
        return tryConsume();
    }

    public int limit() {
        return limit;
    }

    public synchronized int used() {
        return used;
    }
}
