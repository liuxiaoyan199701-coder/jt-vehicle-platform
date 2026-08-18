package io.github.jtplatform.simulator.signal;

import java.time.Instant;

/**
 * 位置的来源。信令客户端每个上报周期问它要一次数据。
 *
 * <p><b>返回 {@code null} 表示本周期不上报</b>。行程的开与停因此不需要重连、也不需要重新调度
 * 上报任务——调度器一直转着，只是拿不到数据就跳过。同样地，尚未开启行程的终端就只是一直返回空，
 * 和从前完全一样。
 *
 * <p>信令层通过这个接口拿到位置，但**不知道位置是怎么来的**：可以是模拟行程，也可以是别的什么。
 * 依赖方向是单向的，信令层不认识行程模块。
 */
@FunctionalInterface
public interface LocationSource {

    /** 永远不上报的来源。未配置行程时用它，语义上等同于「这台终端不汇报位置」。 */
    LocationSource NONE = now -> null;

    /**
     * 取本周期要上报的位置。
     *
     * @param now 本次采样的时刻，由调用方给出。实现据此推进自身状态，因而无需自己读时钟，
     *     也就可以被无时钟地单测
     * @return 位置，或 {@code null} 表示本周期不上报
     */
    LocationFix sample(Instant now);
}
