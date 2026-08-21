package io.github.jtplatform.simulator.signal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import org.yzh.protocol.t808.T0200;

/** 有界盲区位置缓存；容量满时丢弃最旧点。 */
public final class BlindspotBuffer {
    public static final int DEFAULT_CAPACITY = 200;

    private final int capacity;
    private final Deque<T0200> points = new ArrayDeque<>();

    public BlindspotBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public BlindspotBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized void add(T0200 point) {
        points.addLast(Objects.requireNonNull(point, "point"));
        while (points.size() > capacity) {
            points.removeFirst();
        }
    }

    public synchronized List<T0200> drain() {
        List<T0200> result = new ArrayList<>(points);
        points.clear();
        return List.copyOf(result);
    }

    public synchronized int size() {
        return points.size();
    }

    public synchronized void clear() {
        points.clear();
    }
}
