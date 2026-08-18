package io.github.jtplatform.simulator.trip;

import java.util.Objects;

/**
 * 行程状态的只读快照，给界面用。
 *
 * @param connected 会话是否已建立。未建立时不允许开启行程
 * @param running 是否正在行驶并上报
 * @param planning 是否正在获取路线。这段时间最长可达数秒，界面必须给出进度反馈
 * @param finished 单程行程是否已抵达终点
 * @param odometerMeters 已行驶里程，米
 * @param lap 当前第几圈，从 1 开始
 * @param routeDescription 路线来源的名称，未规划时为空串
 * @param explanation 路线来源的说明；降级时含原因与恢复方式
 */
public record TripViewState(
        boolean connected,
        boolean running,
        boolean planning,
        boolean finished,
        double odometerMeters,
        int lap,
        String routeDescription,
        String explanation) {

    public TripViewState {
        Objects.requireNonNull(routeDescription, "routeDescription");
        Objects.requireNonNull(explanation, "explanation");
    }

    public static TripViewState idle() {
        return new TripViewState(false, false, false, false, 0.0D, 1, "", "");
    }

    /**
     * 状态栏文案。
     *
     * <p>**颜色不能是唯一的状态指示**，因此这里始终带文字：色觉障碍用户、以及把窗口截图发出来的
     * 场景，都只能靠文字分辨状态。
     */
    public String summary() {
        if (planning) {
            return "行程 规划路线中…";
        }
        // 「运行中」排在「未连接」之前：自动重连的短暂窗口里行程确实还开着，而且马上会恢复上报。
        // 反过来把它显示成「未连接」，会让人以为行程被断线弄停了。
        if (running) {
            return lap > 1
                    ? "行程 运行中 · %s · 第 %d 圈".formatted(kilometres(), lap)
                    : "行程 运行中 · %s".formatted(kilometres());
        }
        if (finished) {
            return "行程 已完成 · %s".formatted(kilometres());
        }
        if (!connected) {
            return "行程 未连接";
        }
        return "行程 未开始";
    }

    private String kilometres() {
        return "%.1f km".formatted(odometerMeters / 1000.0D);
    }
}
