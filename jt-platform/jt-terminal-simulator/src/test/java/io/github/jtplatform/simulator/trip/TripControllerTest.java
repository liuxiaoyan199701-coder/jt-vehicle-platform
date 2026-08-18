package io.github.jtplatform.simulator.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.TripConfig;
import io.github.jtplatform.simulator.signal.LocationFix;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TripControllerTest {

    private static final Instant START = Instant.parse("2026-08-17T02:00:00Z");
    private static final TripConfig ROUND_TRIP =
            new TripConfig(false, "", null, null, null, null, 60.0D, 10, true);

    @Test
    void reportsNothingUntilTheTripIsStarted() {
        TripController controller = controller();

        assertNull(controller.sample(START), "未开始行程时不该产生任何位置");
        assertFalse(controller.state().running());
    }

    @Test
    void producesAPositionedFixOnceStarted() throws Exception {
        TripController controller = controller();
        controller.start();
        awaitPlanned(controller);

        LocationFix fix = controller.sample(START);

        assertNotNull(fix);
        assertTrue(fix.odometerMeters() > 0, "首个采样点就该往前走，否则看着像卡住了");
        assertTrue(fix.speedKph() > 0);
        assertNotNull(fix.deviceTime());
    }

    /**
     * 设备时间必须是北京时间，而不是开发机器所在时区的时间。
     *
     * <p>平台按北京时间解释这个字段。机器不在东八区时若用系统时区，每个点都会整体偏移若干小时：
     * 数据照样入库、地址也对、日统计也有数，但**按时间段查轨迹一个点都查不到**——这正是线上
     * 实际发生过的那次故障的一半原因。
     */
    @Test
    void stampsDeviceTimeInBeijingRegardlessOfTheMachineTimeZone() throws Exception {
        TripController controller = controller();
        controller.start();
        awaitPlanned(controller);

        LocationFix fix = controller.sample(START);

        LocalDateTime beijing = LocalDateTime.ofInstant(START, ZoneId.of("Asia/Shanghai"));
        assertEquals(beijing, fix.deviceTime());
        // 2026-08-17T02:00:00Z 对应北京时间当日 10:00。
        assertEquals(10, fix.deviceTime().getHour());
        assertEquals(17, fix.deviceTime().getDayOfMonth());
    }

    /**
     * 断线数分钟后重连，第一个采样点的时间跨度是整个断线时长。不截断的话，车会在一个上报周期内
     * 被弹出去几公里，轨迹上留下一条横穿城市的直线。
     */
    @Test
    void clampsAnEnormousGapBetweenSamples() throws Exception {
        TripController controller = controller();
        controller.start();
        awaitPlanned(controller);

        controller.sample(START);
        double afterFirst = controller.state().odometerMeters();
        controller.sample(START.plus(Duration.ofMinutes(30)));
        double afterGap = controller.state().odometerMeters();

        // 上报间隔 10 秒、上限 3 倍、时速 60 km/h ⇒ 单次最多 500 米。
        assertTrue(afterGap - afterFirst <= 501.0D,
                "30 分钟的断线不该让车瞬移，实际前进了 " + (afterGap - afterFirst) + " 米");
        assertTrue(afterGap - afterFirst > 400.0D);
    }

    @Test
    void keepsMileageWhenStoppedAndResumed() throws Exception {
        TripController controller = controller();
        controller.start();
        awaitPlanned(controller);
        controller.sample(START);
        controller.sample(START.plusSeconds(10));
        double travelled = controller.state().odometerMeters();

        controller.stop();
        assertNull(controller.sample(START.plusSeconds(20)), "停止后不该继续上报");
        controller.start();

        assertEquals(travelled, controller.state().odometerMeters(), 1e-9D,
                "重新开始不该把里程清零");
        assertTrue(controller.state().running());
    }

    /** 重复点击「开始」不该重新规划路线，更不该把里程清零。 */
    @Test
    void startIsIdempotent() throws Exception {
        AtomicInteger plans = new AtomicInteger();
        TripController controller = new TripController(
                new RoutePlanner(countingDirections(plans)), state -> { });
        // 带上密钥，路线解析才会真的去调服务——不带的话它直接用内置离线路线，计数永远是 0。
        controller.configure(new TripConfig(
                false, "a-key", null, null, null, null, 60.0D, 10, true));

        controller.start();
        awaitPlanned(controller);
        controller.sample(START);
        controller.start();
        controller.start();

        assertEquals(1, plans.get(), "路线只该规划一次");
        assertTrue(controller.state().odometerMeters() > 0);
    }

    @Test
    void startsAutomaticallyOnlyWhenConfiguredTo() throws Exception {
        TripController manual = controller();
        manual.onSessionEstablished();
        assertFalse(manual.state().running());
        assertTrue(manual.state().connected());

        TripController automatic = new TripController(new RoutePlanner(offline()), state -> { });
        automatic.configure(new TripConfig(
                true, "", null, null, null, null, 60.0D, 10, true));
        automatic.onSessionEstablished();
        awaitPlanned(automatic);

        assertTrue(automatic.state().running());
    }

    @Test
    void stopsReportingWhenAOneWayTripReachesItsDestination() throws Exception {
        TripController controller = new TripController(new RoutePlanner(offline()), state -> { });
        controller.configure(new TripConfig(
                false, "", null, null, null, null, 300.0D, 600, false));
        controller.start();
        awaitPlanned(controller);

        // 时速 300 km/h、间隔 600 秒 ⇒ 单次最多 50 km，足以跑完 5.8 km 的内置路线。
        controller.sample(START);

        assertTrue(controller.state().finished());
        assertFalse(controller.state().running());
        assertNull(controller.sample(START.plusSeconds(600)), "行程结束后不该继续上报");
        assertTrue(controller.state().summary().contains("已完成"));
    }

    /**
     * 网络抖动要撑过去，用户点「断开」则停止。两者都把 {@code connected} 置为 false，
     * 区别在于行程本身是否继续。
     */
    @Test
    void survivesAReconnectButStopsOnAnIntentionalDisconnect() throws Exception {
        TripController controller = controller();
        controller.onSessionEstablished();
        controller.start();
        awaitPlanned(controller);
        controller.sample(START);
        double travelled = controller.state().odometerMeters();

        controller.onSessionLost();
        assertTrue(controller.state().running(), "自动重连不该打断行程");
        assertFalse(controller.state().connected());
        controller.onSessionEstablished();
        controller.sample(START.plusSeconds(10));
        assertTrue(controller.state().odometerMeters() > travelled,
                "重连后应当从中断处继续，而不是从起点重来");

        controller.onSessionClosed();
        assertFalse(controller.state().running(), "用户主动断开应当停止行程");
        assertEquals(travelled, controller.state().odometerMeters(), 500.0D,
                "停止不该把里程清零");
    }

    /**
     * 改完起终点再开始，必须跑新路线。
     *
     * <p>沿用旧路线在界面上看不出任何异常——车照跑、里程照涨，只是走的路和刚填的完全无关，
     * 而用户多半会以为是坐标填错了。
     */
    @Test
    void discardsThePlannedRouteWhenTheConfigurationChanges() throws Exception {
        AtomicInteger plans = new AtomicInteger();
        TripController controller = new TripController(
                new RoutePlanner(countingDirections(plans)), state -> { });
        controller.configure(new TripConfig(
                false, "a-key", null, null, null, null, 60.0D, 10, true));
        controller.start();
        awaitPlanned(controller);
        controller.sample(START);
        assertTrue(controller.state().odometerMeters() > 0);

        controller.configure(new TripConfig(
                false, "a-key", 31.23D, 121.47D, 31.30D, 121.55D, 60.0D, 10, true));

        assertFalse(controller.state().running(), "配置变更后应当回到未开始");
        assertEquals(0.0D, controller.state().odometerMeters(), 0.0D);
        controller.start();
        awaitPlanned(controller);
        assertEquals(2, plans.get(), "新配置必须重新规划路线");
    }

    /** 原样重新保存不该把跑了半天的里程清零。 */
    @Test
    void keepsTheRouteWhenTheConfigurationIsUnchanged() throws Exception {
        TripController controller = controller();
        controller.start();
        awaitPlanned(controller);
        controller.sample(START);
        double travelled = controller.state().odometerMeters();

        controller.configure(ROUND_TRIP);

        assertEquals(travelled, controller.state().odometerMeters(), 0.0D);
        assertTrue(controller.state().running());
    }

    @Test
    void publishesStateChangesToTheListener() throws Exception {
        List<TripViewState> published = new ArrayList<>();
        TripController controller = new TripController(
                new RoutePlanner(offline()), published::add);
        controller.configure(ROUND_TRIP);

        controller.start();
        awaitPlanned(controller);

        assertTrue(published.stream().anyMatch(TripViewState::planning),
                "界面需要知道正在规划路线，否则会以为点击没生效");
        assertTrue(published.getLast().running());
        assertTrue(published.getLast().explanation().contains("内置"));
    }

    /** 界面回调抛异常不该拖垮行程状态机。 */
    @Test
    void survivesAListenerThatThrows() throws Exception {
        TripController controller = new TripController(new RoutePlanner(offline()), state -> {
            throw new IllegalStateException("界面炸了");
        });
        controller.configure(ROUND_TRIP);

        controller.start();
        awaitPlanned(controller);

        assertNotNull(controller.sample(START));
    }

    private static TripController controller() {
        TripController controller = new TripController(new RoutePlanner(offline()), state -> { });
        controller.configure(ROUND_TRIP);
        return controller;
    }

    /** 永不成功的路线服务，强制降级到内置离线路线——测试因此不联网。 */
    private static DirectionsService offline() {
        return (origin, destination, key) -> {
            throw new AmapException("测试不联网");
        };
    }

    private static DirectionsService countingDirections(AtomicInteger plans) {
        return (origin, destination, key) -> {
            plans.incrementAndGet();
            throw new AmapException("测试不联网");
        };
    }

    /**
     * 路线规划在后台线程完成，等它落地。
     *
     * <p>这里只能轮询：规划完成没有对外的同步点，而给生产代码加一个只为测试服务的闩锁，
     * 比在测试里等一小会儿更糟。
     */
    private static void awaitPlanned(TripController controller) throws InterruptedException {
        for (int attempt = 0; attempt < 200 && controller.state().planning(); attempt++) {
            Thread.sleep(10);
        }
        assertFalse(controller.state().planning(), "路线规划没有在预期时间内完成");
    }
}
