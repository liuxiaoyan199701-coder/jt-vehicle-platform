package io.github.jtplatform.delivery.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jtplatform.delivery.model.MessageType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class DeliveryMetricsRegistryTest {
    @Test
    void exposesAllRequiredChannelMetricsToMicrometer() {
        ChannelDeliveryMetrics metrics = new ChannelDeliveryMetrics("api");
        metrics.recordSuccess();
        metrics.recordFailure();
        metrics.recordRetry();
        metrics.recordDropped(MessageType.LOCATION);
        metrics.incrementBacklog();
        DeliveryMetricsRegistry deliveryRegistry = new DeliveryMetricsRegistry();
        deliveryRegistry.register(metrics);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        deliveryRegistry.bindTo(meterRegistry);

        assertEquals(1.0, meterRegistry.get("jt.delivery.messages")
                .tags("channel", "api", "outcome", "success").functionCounter().count());
        assertEquals(1.0, meterRegistry.get("jt.delivery.messages")
                .tags("channel", "api", "outcome", "failure").functionCounter().count());
        assertEquals(1.0, meterRegistry.get("jt.delivery.messages")
                .tags("channel", "api", "outcome", "retry").functionCounter().count());
        assertEquals(1.0, meterRegistry.get("jt.delivery.messages")
                .tags("channel", "api", "outcome", "dropped").functionCounter().count());
        assertEquals(1.0, meterRegistry.get("jt.delivery.queue.backlog").tag("channel", "api").gauge().value());
        assertEquals(1L, deliveryRegistry.snapshots().get("api").droppedByType().get(MessageType.LOCATION));
    }
}
