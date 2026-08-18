package io.github.jtplatform.simulator.trip;

import java.util.Objects;

/**
 * 按时间增量沿路线推进车辆，支持到终点掉头往返。
 *
 * <p><b>整个行车状态压缩成一个标量</b>：已行驶总距离。位置、航向、圈数、是否结束，全都是它的纯函数。
 * 之所以不额外维护「当前段索引」「段内余量」「当前朝向」这些分量：它们之间必须互相自洽，而每多一个
 * 需要同步更新的分量，就多一处它们可能对不上的地方。单一标量没有「不自洽」这个状态。
 *
 * <p>往返被建模成周期为「两倍路线长」的折返运动：前半周期从起点走到终点，后半周期原路走回。因此
 * 任意大的时间增量都只是一次取模，而不是循环折返——断线十分钟后重连的第一个 tick 不会把这里卡死。
 *
 * <p><b>本类不碰时钟</b>，时间增量由调用方给。这是它能被无时钟纯单测的前提，也让「快进两小时」
 * 这类测试变成一次普通调用。
 */
public final class TripAdvancer {

    private static final double KPH_TO_MPS = 1000.0D / 3600.0D;

    private final Route route;
    private final double speedMps;
    private final double speedKph;
    private final boolean roundTrip;

    /** 已行驶总距离，米。单调不减，往返时跨圈继续累加——与真实里程表语义一致。 */
    private double distanceMeters;

    public TripAdvancer(Route route, double speedKph, boolean roundTrip) {
        this.route = Objects.requireNonNull(route, "route");
        if (!Double.isFinite(speedKph) || speedKph <= 0.0D) {
            throw new IllegalArgumentException("速度必须是正的有限值：" + speedKph);
        }
        this.speedKph = speedKph;
        this.speedMps = speedKph * KPH_TO_MPS;
        this.roundTrip = roundTrip;
    }

    /**
     * 推进 {@code seconds} 秒。单程模式下抵达终点即停住，再推进也不动。
     *
     * @throws IllegalArgumentException 时间增量为负或非有限值——这属于调用方的编程错误，
     *     不该被悄悄当成 0 吞掉
     */
    public void advance(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.0D) {
            throw new IllegalArgumentException("时间增量必须是非负的有限值：" + seconds);
        }
        double next = distanceMeters + speedMps * seconds;
        if (!Double.isFinite(next)) {
            // 时间增量大到让距离溢出。夹到单程终点，宁可停住也不要让 NaN 传到协议编码里。
            next = route.lengthMeters();
        }
        distanceMeters = roundTrip ? next : Math.min(next, route.lengthMeters());
    }

    /** 当前坐标。 */
    public GeoPoint position() {
        return route.pointAt(arcLength());
    }

    /**
     * 当前航向，正北为 0，顺时针，0..359。
     *
     * <p>返程时直接把正向航向加 180 度，而不是拿反向点集重算：反向重算会因为浮点误差得到
     * 179 或 181 这种与正向不严格互补的值，肉眼看不出来，但会让「掉头后航向互补」这条测试变得脆弱。
     */
    public int bearing() {
        int forward = route.bearingAt(arcLength());
        return returning() ? (forward + 180) % 360 : forward;
    }

    /** 已行驶总里程，米。往返不重置。 */
    public double odometerMeters() {
        return distanceMeters;
    }

    /**
     * 当前是第几圈，从 1 开始。一圈 = 去 + 回。单程模式恒为 1。
     */
    public int lap() {
        if (!roundTrip) {
            return 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, (long) (distanceMeters / cycleLength()) + 1);
    }

    /** 单程模式下是否已抵达终点。往返模式永远返回 {@code false}。 */
    public boolean finished() {
        return !roundTrip && distanceMeters >= route.lengthMeters();
    }

    /** 是否正在返程。 */
    public boolean returning() {
        if (!roundTrip) {
            return false;
        }
        return distanceMeters % cycleLength() > route.lengthMeters();
    }

    /** 当前速度，km/h。已抵达终点时为 0——停住的车不该还报着 60。 */
    public double currentSpeedKph() {
        return finished() ? 0.0D : speedKph;
    }

    public Route route() {
        return route;
    }

    /**
     * 把「已行驶总距离」折算成「距路线起点的弧长」。
     *
     * <p>往返即周期运动：一个周期内前半程弧长随距离递增，后半程递减。
     */
    private double arcLength() {
        double length = route.lengthMeters();
        if (!roundTrip) {
            return Math.min(distanceMeters, length);
        }
        double withinCycle = distanceMeters % cycleLength();
        return withinCycle <= length ? withinCycle : cycleLength() - withinCycle;
    }

    private double cycleLength() {
        return route.lengthMeters() * 2.0D;
    }
}
