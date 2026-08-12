package io.github.jtplatform.delivery.metrics;

import io.github.jtplatform.delivery.model.MessageType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record DeliveryMetricsSnapshot(
        String channel,
        long success,
        long failure,
        long retries,
        long dropped,
        long backlog,
        long backpressured,
        Map<MessageType, Long> droppedByType) {

    public DeliveryMetricsSnapshot {
        channel = Objects.requireNonNull(channel, "channel");
        EnumMap<MessageType, Long> copy = new EnumMap<>(MessageType.class);
        copy.putAll(Objects.requireNonNull(droppedByType, "droppedByType"));
        droppedByType = Collections.unmodifiableMap(copy);
    }
}
