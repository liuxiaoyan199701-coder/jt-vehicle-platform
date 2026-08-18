package io.github.jtplatform.simulator.trip;

import java.util.Objects;

/**
 * 一次路线解析的结果：拿到的路线，加一句给用户看的说明。
 *
 * @param route 可直接行驶的路线，永远不为空
 * @param explanation 一句人话，说明用的是哪条路线；降级时还要说清原因与怎么恢复
 * @param degraded 是否发生了降级。调用方据此决定日志级别——降级值得记 WARN，正常规划不值得
 */
public record RoutePlan(Route route, String explanation, boolean degraded) {

    public RoutePlan {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(explanation, "explanation");
    }
}
