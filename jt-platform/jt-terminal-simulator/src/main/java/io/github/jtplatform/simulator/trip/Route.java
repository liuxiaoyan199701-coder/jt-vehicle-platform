package io.github.jtplatform.simulator.trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一条不可变路线：有序点列 + 预计算的累计弧长。
 *
 * <p>行车位置由单个标量「已行弧长」唯一确定，本类负责把弧长翻译成坐标与航向。之所以按弧长参数化
 * 而不是维护「当前段索引 + 段内剩余距离」的状态机：调度存在抖动，逐段行走会把每次抖动的舍入误差
 * 累积进段索引，跑久了会漂移；弧长参数化下已行弧长是唯一真值，抖动只影响单次采样点。
 *
 * <p>点列存的是**原始坐标系**的点——转换在构造路线时一次性做完，之后的推进与上报都不再碰坐标系，
 * 也就没有「这个点到底转过没有」的疑问。
 */
public final class Route {

    /**
     * 短于此长度的路线没有模拟价值：车会在原地抖动，且航向由浮点噪声决定。
     *
     * <p>公开是为了让表单用同一个阈值提前拦下「起终点几乎重合」，而不是等运行时降级——
     * 两处各写一个数字，迟早会变成「表单放过了运行时又拒绝」的死角。
     */
    public static final double MIN_LENGTH_METERS = 50.0D;

    private final List<GeoPoint> points;
    /** {@code cumulative[i]} 是从起点走到 {@code points[i]} 的弧长；长度与点列相同，首项为 0。 */
    private final double[] cumulative;
    private final String description;

    private Route(List<GeoPoint> points, double[] cumulative, String description) {
        this.points = points;
        this.cumulative = cumulative;
        this.description = description;
    }

    /**
     * 由点列构造路线，顺带剔除重复点。
     *
     * <p>重复点必须剔除：相邻分段的折线首尾天然重合，留着会产生零长度段，而零长度段上的方位角是
     * {@code atan2(0, 0)}，恒返回 0——车头会在经过接缝时瞬间跳向正北。
     *
     * @throws IllegalArgumentException 去重后不足两点，或总长过短
     */
    public static Route of(List<GeoPoint> rawPoints, String description) {
        Objects.requireNonNull(rawPoints, "rawPoints");
        Objects.requireNonNull(description, "description");

        List<GeoPoint> kept = new ArrayList<>(rawPoints.size());
        for (GeoPoint point : rawPoints) {
            Objects.requireNonNull(point, "route point");
            if (kept.isEmpty() || CoordinateTransform.distanceMeters(kept.getLast(), point) > 0.0D) {
                kept.add(point);
            }
        }
        if (kept.size() < 2) {
            throw new IllegalArgumentException("路线至少需要两个不同的点，实际 " + kept.size());
        }

        double[] cumulative = new double[kept.size()];
        for (int i = 1; i < kept.size(); i++) {
            cumulative[i] = cumulative[i - 1]
                    + CoordinateTransform.distanceMeters(kept.get(i - 1), kept.get(i));
        }
        double total = cumulative[cumulative.length - 1];
        if (total < MIN_LENGTH_METERS) {
            throw new IllegalArgumentException(
                    "路线总长 %.1f 米，短于可模拟的最小长度 %.0f 米".formatted(total, MIN_LENGTH_METERS));
        }
        return new Route(List.copyOf(kept), cumulative, description);
    }

    /**
     * 由**加密坐标系**的点列构造路线，转换到原始坐标系后再交给 {@link #of}。
     *
     * <p>地图服务给出的点都要走这个入口。之所以单独开一个具名入口而不是让调用方自己先转一遍：
     * 「这批点转过没有」是本模块唯一一处看不出对错的地方——转两次和没转都不会报错，只会让车偏几百米。
     * 入口名字里带着坐标系，就没有「忘了转」这个选项。
     */
    public static Route fromEncrypted(List<GeoPoint> encryptedPoints, String description) {
        Objects.requireNonNull(encryptedPoints, "encryptedPoints");
        List<GeoPoint> plain = new ArrayList<>(encryptedPoints.size());
        for (GeoPoint point : encryptedPoints) {
            plain.add(CoordinateTransform.toPlain(Objects.requireNonNull(point, "route point")));
        }
        return of(plain, description);
    }

    /** 路线总长，米。 */
    public double lengthMeters() {
        return cumulative[cumulative.length - 1];
    }

    /**
     * 路线来源的人话名称，例如「驾车路径规划」「内置路线」。
     *
     * <p>刻意只放来源、不放长度：长度随时可以从 {@link #lengthMeters()} 取，写进描述里就成了
     * 一个会与实际长度悄悄对不上的副本。
     */
    public String description() {
        return description;
    }

    public List<GeoPoint> points() {
        return points;
    }

    /**
     * 取距起点 {@code s} 米处的坐标。{@code s} 会先截断到 {@code [0, 总长]}。
     */
    public GeoPoint pointAt(double s) {
        double clamped = clamp(s);
        int segment = segmentIndexFor(clamped);
        double segmentStart = cumulative[segment];
        double segmentLength = cumulative[segment + 1] - segmentStart;
        // 去重保证了段长严格为正，这里不会除零。
        double ratio = (clamped - segmentStart) / segmentLength;
        GeoPoint from = points.get(segment);
        GeoPoint to = points.get(segment + 1);
        return new GeoPoint(
                from.lat() + (to.lat() - from.lat()) * ratio,
                from.lng() + (to.lng() - from.lng()) * ratio);
    }

    /**
     * 取距起点 {@code s} 米处的行进方位角，正北为 0，顺时针。
     *
     * <p>取的是所在**整段**的方位角，而不是采样点前后的瞬时方向：段内是直线，整段方位角才是这一段
     * 真正的行驶方向，且不会因为采样点恰好落在段端而抖动。
     */
    public int bearingAt(double s) {
        int segment = segmentIndexFor(clamp(s));
        return CoordinateTransform.bearingDegrees(points.get(segment), points.get(segment + 1));
    }

    private double clamp(double s) {
        if (!Double.isFinite(s)) {
            throw new IllegalArgumentException("弧长必须是有限值：" + s);
        }
        return Math.min(Math.max(s, 0.0D), lengthMeters());
    }

    /**
     * 二分查找 {@code s} 落在哪一段，返回段起点的下标，取值范围 {@code [0, 点数-2]}。
     */
    private int segmentIndexFor(double s) {
        int low = 0;
        int high = cumulative.length - 1;
        // 找到最后一个满足 cumulative[i] <= s 的 i。
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (cumulative[mid] <= s) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        // s 正好等于总长时会落在最后一个点上，回退一段，保证段起点后面还有终点。
        return Math.min(low, cumulative.length - 2);
    }
}
