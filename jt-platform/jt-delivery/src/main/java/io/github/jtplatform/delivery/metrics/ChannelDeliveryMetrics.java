package io.github.jtplatform.delivery.metrics;

import io.github.jtplatform.delivery.model.MessageType;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class ChannelDeliveryMetrics implements DeliveryMetrics {
    private final String channel;
    private final LongAdder success = new LongAdder();
    private final LongAdder failure = new LongAdder();
    private final LongAdder retries = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder backpressured = new LongAdder();
    private final AtomicLong backlog = new AtomicLong();
    private final Map<MessageType, LongAdder> droppedByType = new EnumMap<>(MessageType.class);

    public ChannelDeliveryMetrics(String channel) {
        this.channel = channel;
        for (MessageType type : MessageType.values()) {
            droppedByType.put(type, new LongAdder());
        }
    }

    public void recordSuccess() {
        success.increment();
    }

    public void recordFailure() {
        failure.increment();
    }

    public void recordRetry() {
        retries.increment();
    }

    public long recordDropped(MessageType type) {
        dropped.increment();
        LongAdder typeCounter = droppedByType.get(type);
        typeCounter.increment();
        return typeCounter.sum();
    }

    public long recordBackpressured() {
        backpressured.increment();
        return backpressured.sum();
    }

    public void incrementBacklog() {
        backlog.incrementAndGet();
    }

    public void decrementBacklog() {
        backlog.decrementAndGet();
    }

    @Override
    public DeliveryMetricsSnapshot snapshot() {
        EnumMap<MessageType, Long> drops = new EnumMap<>(MessageType.class);
        droppedByType.forEach((type, counter) -> {
            long count = counter.sum();
            if (count > 0) {
                drops.put(type, count);
            }
        });
        return new DeliveryMetricsSnapshot(channel, success.sum(), failure.sum(), retries.sum(), dropped.sum(),
                backlog.get(), backpressured.sum(), drops);
    }
}
