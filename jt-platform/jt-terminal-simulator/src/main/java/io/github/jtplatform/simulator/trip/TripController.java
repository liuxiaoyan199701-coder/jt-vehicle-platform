package io.github.jtplatform.simulator.trip;

import io.github.jtplatform.simulator.config.TerminalTime;
import io.github.jtplatform.simulator.config.TripConfig;
import io.github.jtplatform.simulator.signal.LocationFix;
import io.github.jtplatform.simulator.signal.LocationSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 行程的运行时。对信令层是一个 {@link LocationSource}，对界面是启停入口与状态来源。
 *
 * <p><b>它挂在应用运行时上，而不是挂在某一条连接上</b>，因此断线重连不会中断行程：重连后新连接
 * 自动重新调度上报，里程与进度从中断处继续，而不是从起点重来。
 *
 * <p>与 {@code MediaController} 同构：由运行时构造一次，注入每个信令客户端。
 */
public final class TripController implements LocationSource {

    /**
     * 时间增量的上限倍数。断线数分钟后重连，第一个采样点的时间跨度会是整个断线时长——
     * 不截断的话，车会在一个上报周期内被弹出去几公里，轨迹上留下一条横穿城市的直线。
     */
    private static final int MAX_STEP_INTERVALS = 3;

    /**
     * 位置汇报里的时间是**终端本地时间**，而平台按北京时间解释它（控制台的时钟容差注释里写明了
     * 这一点：设备时间是北京时间、接收时间是 UTC，所以容差必须大于 8 小时）。
     *
     * <p>因此这里固定用北京时区，而不是 {@code ZoneId.systemDefault()}——模拟的是一台国内营运
     * 车辆的终端，它报的时间跟开发机器所在的时区没有关系。用系统时区的话，机器不在东八区时每个
     * 轨迹点都会整体偏移若干小时：数据照样入库、地址也对，但时间轴对不上，按时间段查就查不到。
     */
    private static final ZoneOffset TERMINAL_ZONE = TerminalTime.ZONE;

    private final RoutePlanner planner;
    private final Consumer<TripViewState> listener;
    private final ExecutorService planningExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("jt-simulator-trip-planner-", 0).factory());

    private final Object lock = new Object();

    private volatile boolean connected;
    private TripConfig config = TripConfig.defaults();

    private TripAdvancer advancer;
    private Instant lastSample;
    private boolean planning;
    private boolean running;
    private String explanation = "";

    public TripController(RoutePlanner planner, Consumer<TripViewState> listener) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.listener = listener == null ? state -> { } : listener;
    }

    /**
     * 配置变更。
     *
     * <p>配置真的变了就**丢弃已规划的路线**，否则改完起终点再开始，跑的还是上一条路线——
     * 界面上看不出任何异常，只是车走的路和刚填的完全无关。配置没变则保留路线与里程，
     * 免得一次无谓的重新保存把跑了半天的里程清零。
     */
    public void configure(TripConfig config) {
        TripConfig next = Objects.requireNonNull(config, "config");
        synchronized (lock) {
            if (next.equals(this.config)) {
                return;
            }
            this.config = next;
            advancer = null;
            lastSample = null;
            running = false;
            explanation = "";
            publishLocked();
        }
    }

    /** 会话状态变化。断开时停止行程，但不清空里程——重连后从中断处继续。 */
    public void onSessionEstablished() {
        connected = true;
        boolean autoStart;
        synchronized (lock) {
            autoStart = config.autoStart();
        }
        if (autoStart) {
            start();
        } else {
            publish();
        }
    }

    /**
     * 会话中断。**不停止行程**——自动重连后新连接会重新调度上报，里程与进度从中断处继续。
     * 这里停掉的话，每一次网络抖动都会把行程打断，而重连本来是无感的。
     */
    public void onSessionLost() {
        connected = false;
        publish();
    }

    /**
     * 用户主动断开连接。这一条与 {@link #onSessionLost()} 的区别是**用户意图**：网络抖动要
     * 撑过去，用户点「断开」则是明确不想再跑了，行程随之停止。
     */
    public void onSessionClosed() {
        connected = false;
        stop();
    }

    /**
     * 开始行程。幂等——重复调用不会重新规划路线，也不会重置里程。
     *
     * <p>路线规划要访问外部服务，最长可能等待数秒，因此放到后台线程；界面在这段时间里显示
     * 「规划路线中…」并给出进度反馈。
     */
    public void start() {
        TripConfig snapshot;
        synchronized (lock) {
            if (running || planning) {
                return;
            }
            if (advancer != null) {
                // 已经规划过路线，继续跑就是了——停了再开不该重新规划，更不该把里程清零。
                running = true;
                lastSample = null;
                publishLocked();
                return;
            }
            planning = true;
            snapshot = config;
            publishLocked();
        }
        planningExecutor.execute(() -> {
            RoutePlan plan = planner.resolve(snapshot);
            synchronized (lock) {
                planning = false;
                advancer = new TripAdvancer(plan.route(), snapshot.speedKph(), snapshot.roundTrip());
                lastSample = null;
                running = true;
                explanation = plan.explanation();
                publishLocked();
            }
        });
    }

    /** 停止行程。幂等。里程与进度保留，再次开始时从原处继续。 */
    public void stop() {
        synchronized (lock) {
            running = false;
            lastSample = null;
            publishLocked();
        }
    }

    /** 重置行程：丢弃路线与里程，下次开始时重新规划。 */
    public void reset() {
        synchronized (lock) {
            running = false;
            advancer = null;
            lastSample = null;
            explanation = "";
            publishLocked();
        }
    }

    public TripViewState state() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    @Override
    public LocationFix sample(Instant now) {
        Objects.requireNonNull(now, "now");
        synchronized (lock) {
            if (!running || advancer == null) {
                return null;
            }
            double seconds = elapsedSecondsLocked(now);
            lastSample = now;
            advancer.advance(seconds);
            if (advancer.finished()) {
                running = false;
            }
            GeoPoint position = advancer.position();
            LocationFix fix = new LocationFix(
                    position.lat(),
                    position.lng(),
                    0,
                    advancer.currentSpeedKph(),
                    advancer.bearing(),
                    advancer.odometerMeters(),
                    LocalDateTime.ofInstant(now, TERMINAL_ZONE));
            publishLocked();
            return fix;
        }
    }

    /**
     * 距上次采样过去了多少秒，截断到上报周期的若干倍。
     *
     * <p>首个采样点没有「上次」可比，按一个上报周期计——从 0 开始会让第一个点永远落在起点上，
     * 看着像卡住了。
     */
    private double elapsedSecondsLocked(Instant now) {
        double interval = Math.max(1, config.reportIntervalSeconds());
        if (lastSample == null) {
            return interval;
        }
        double actual = (now.toEpochMilli() - lastSample.toEpochMilli()) / 1000.0D;
        return Math.min(Math.max(actual, 0.0D), interval * MAX_STEP_INTERVALS);
    }

    private void publishLocked() {
        TripViewState snapshot = snapshotLocked();
        // 回调可能是界面代码，抛异常不该拖垮行程。
        try {
            listener.accept(snapshot);
        } catch (RuntimeException ignored) {
            // 界面失败不影响行程状态机。
        }
    }

    private void publish() {
        synchronized (lock) {
            publishLocked();
        }
    }

    private TripViewState snapshotLocked() {
        boolean finished = advancer != null && advancer.finished();
        return new TripViewState(
                connected,
                running,
                planning,
                finished,
                advancer == null ? 0.0D : advancer.odometerMeters(),
                advancer == null ? 1 : advancer.lap(),
                advancer == null ? "" : advancer.route().description(),
                explanation);
    }
}
