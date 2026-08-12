package io.github.jtplatform.simulator.media;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

public final class MediaFrameQueue {
    public static final Duration DEFAULT_REORDER_WINDOW = Duration.ofMillis(60);

    private static final Comparator<Entry> ORDER = Comparator
            .comparingLong((Entry entry) -> entry.frame().timestampMillis())
            .thenComparingLong(Entry::order);

    private final int capacity;
    private final long reorderMillis;
    private final MediaStats stats;
    private final PriorityQueue<Entry> frames = new PriorityQueue<>(ORDER);
    private long insertionOrder;
    private long latestTimestamp = -1L;
    private boolean awaitingKeyFrame;

    public MediaFrameQueue(int capacity, MediaStats stats) {
        this(capacity, DEFAULT_REORDER_WINDOW, stats);
    }

    public MediaFrameQueue(int capacity, Duration reorderWindow, MediaStats stats) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        Objects.requireNonNull(reorderWindow, "reorderWindow");
        if (reorderWindow.isNegative() || reorderWindow.toMillis() < 1) {
            throw new IllegalArgumentException("reorderWindow must be at least one millisecond");
        }
        this.capacity = capacity;
        this.reorderMillis = reorderWindow.toMillis();
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    public synchronized boolean offer(MediaFrame frame) {
        Objects.requireNonNull(frame, "frame");
        stats.recordProduced(frame);
        latestTimestamp = Math.max(latestTimestamp, frame.timestampMillis());

        if (awaitingKeyFrame && frame.type() == MediaFrameType.VIDEO_P) {
            stats.recordDropped(frame);
            return false;
        }
        if (frame.type() == MediaFrameType.VIDEO_I) {
            awaitingKeyFrame = false;
        }

        if (frames.size() >= capacity && !makeRoom(frame)) {
            stats.recordDropped(frame);
            return false;
        }
        frames.add(new Entry(frame, insertionOrder++));
        stats.recordQueueDepth(frames.size());
        notifyAll();
        return true;
    }

    public synchronized Optional<MediaFrame> pollReady(long nowMillis) {
        Entry first = frames.peek();
        if (first == null) {
            return Optional.empty();
        }
        long watermark = Math.max(latestTimestamp, nowMillis);
        if (watermark - first.frame().timestampMillis() < reorderMillis) {
            return Optional.empty();
        }
        return Optional.of(removeFirst());
    }

    public synchronized List<MediaFrame> drainReady(long nowMillis) {
        List<MediaFrame> ready = new ArrayList<>();
        Optional<MediaFrame> frame;
        while ((frame = pollReady(nowMillis)).isPresent()) {
            ready.add(frame.orElseThrow());
        }
        return List.copyOf(ready);
    }

    public synchronized Optional<MediaFrame> pollAny() {
        return frames.isEmpty() ? Optional.empty() : Optional.of(removeFirst());
    }

    public synchronized int size() {
        return frames.size();
    }

    public int capacity() {
        return capacity;
    }

    public long reorderMillis() {
        return reorderMillis;
    }

    public synchronized void clear() {
        frames.clear();
        latestTimestamp = -1L;
        awaitingKeyFrame = false;
        stats.recordQueueDepth(0);
    }

    private boolean makeRoom(MediaFrame incoming) {
        if (incoming.type() == MediaFrameType.VIDEO_P) {
            awaitingKeyFrame = true;
            return false;
        }

        List<Entry> removedPFrames = removeAll(MediaFrameType.VIDEO_P);
        if (!removedPFrames.isEmpty()) {
            removedPFrames.forEach(entry -> stats.recordDropped(entry.frame()));
            awaitingKeyFrame = incoming.type() != MediaFrameType.VIDEO_I;
        }
        if (frames.size() < capacity) {
            return true;
        }

        Entry expendable = findOldest(incoming.type() == MediaFrameType.VIDEO_I
                ? MediaFrameType.AUDIO : incoming.type());
        if (expendable == null) {
            return false;
        }
        frames.remove(expendable);
        stats.recordDropped(expendable.frame());
        return true;
    }

    private List<Entry> removeAll(MediaFrameType type) {
        List<Entry> removed = new ArrayList<>();
        Iterator<Entry> iterator = frames.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.frame().type() == type) {
                iterator.remove();
                removed.add(entry);
            }
        }
        return removed;
    }

    private Entry findOldest(MediaFrameType type) {
        return frames.stream()
                .filter(entry -> entry.frame().type() == type)
                .min(ORDER)
                .orElse(null);
    }

    private MediaFrame removeFirst() {
        MediaFrame frame = frames.remove().frame();
        stats.recordQueueDepth(frames.size());
        return frame;
    }

    private record Entry(MediaFrame frame, long order) {
    }
}
