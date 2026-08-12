package io.github.jtplatform.delivery.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DeliveryMetricsRegistry implements MeterBinder {
    private final Map<String, DeliveryMetrics> channels = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<MeterRegistry> meterRegistries = new CopyOnWriteArrayList<>();

    public void register(DeliveryMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
        String channel = metrics.snapshot().channel();
        DeliveryMetrics previous = channels.putIfAbsent(channel, metrics);
        if (previous != null && previous != metrics) {
            throw new IllegalStateException("delivery metrics already registered for channel " + channel);
        }
        if (previous == null) {
            meterRegistries.forEach(registry -> bindChannel(registry, metrics));
        }
    }

    public Map<String, DeliveryMetricsSnapshot> snapshots() {
        LinkedHashMap<String, DeliveryMetricsSnapshot> snapshots = new LinkedHashMap<>();
        channels.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshots.put(entry.getKey(), entry.getValue().snapshot()));
        return Collections.unmodifiableMap(snapshots);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        if (meterRegistries.addIfAbsent(registry)) {
            channels.values().forEach(metrics -> bindChannel(registry, metrics));
        }
    }

    private static void bindChannel(MeterRegistry registry, DeliveryMetrics metrics) {
        String channel = metrics.snapshot().channel();
        bindCounter(registry, metrics, channel, "success", DeliveryMetricsSnapshot::success);
        bindCounter(registry, metrics, channel, "failure", DeliveryMetricsSnapshot::failure);
        bindCounter(registry, metrics, channel, "retry", DeliveryMetricsSnapshot::retries);
        bindCounter(registry, metrics, channel, "dropped", DeliveryMetricsSnapshot::dropped);
        bindCounter(registry, metrics, channel, "backpressured", DeliveryMetricsSnapshot::backpressured);
        Gauge.builder("jt.delivery.queue.backlog", metrics, value -> value.snapshot().backlog())
                .description("Messages waiting or in flight in a delivery channel")
                .tag("channel", channel)
                .register(registry);
    }

    private static void bindCounter(
            MeterRegistry registry,
            DeliveryMetrics metrics,
            String channel,
            String outcome,
            java.util.function.ToDoubleFunction<DeliveryMetricsSnapshot> value) {
        FunctionCounter.builder("jt.delivery.messages", metrics, current -> value.applyAsDouble(current.snapshot()))
                .description("Delivery message outcomes")
                .tag("channel", channel)
                .tag("outcome", outcome)
                .register(registry);
    }
}
