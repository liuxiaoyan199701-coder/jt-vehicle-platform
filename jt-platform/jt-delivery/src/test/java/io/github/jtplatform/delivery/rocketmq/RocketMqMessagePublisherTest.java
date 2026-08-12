package io.github.jtplatform.delivery.rocketmq;

import static io.github.jtplatform.delivery.TestEnvelopes.envelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.delivery.channel.AsyncChannelOptions;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class RocketMqMessagePublisherTest {
    @Test
    void retriesOnTheSameStableQueueAndPreservesProducerOrder() throws Exception {
        List<RocketMqQueue> queues = List.of(new RocketMqQueue("broker-b", 0),
                new RocketMqQueue("broker-a", 1), new RocketMqQueue("broker-a", 0));
        CopyOnWriteArrayList<Integer> serials = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<RocketMqQueue> selected = new CopyOnWriteArrayList<>();
        AtomicInteger firstAttempts = new AtomicInteger();
        AtomicInteger queueLookups = new AtomicInteger();
        RocketMqTransport transport = new RocketMqTransport() {
            @Override
            public List<RocketMqQueue> availableQueues(String topic) {
                queueLookups.incrementAndGet();
                ArrayList<RocketMqQueue> varyingOrder = new ArrayList<>(queues);
                if (queueLookups.get() % 2 == 0) {
                    java.util.Collections.reverse(varyingOrder);
                }
                return varyingOrder;
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> send(
                    String topic, RocketMqQueue queue, String messageKey, MessageEnvelope message) {
                assertEquals("jt-events", topic);
                assertEquals(message.eventId(), messageKey);
                serials.add(message.serialNo());
                selected.add(queue);
                if (message.serialNo() == 1 && firstAttempts.incrementAndGet() == 1) {
                    return CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        AsyncChannelOptions options = new AsyncChannelOptions(16, 2, 4, 100, Duration.ofMillis(50),
                Duration.ofMillis(5), Duration.ofMillis(20), Duration.ofSeconds(1), Duration.ofMillis(100));
        try (RocketMqMessagePublisher publisher = new RocketMqMessagePublisher("jt-events", transport,
                new DeviceQueueSelector(), options)) {
            publisher.publish(envelope("device-1", 1, MessageType.ALARM));
            publisher.publish(envelope("device-1", 2, MessageType.ALARM));

            assertTrue(publisher.awaitIdle(Duration.ofSeconds(2)));
            assertEquals(List.of(1, 1, 2), serials);
            assertEquals(selected.getFirst(), selected.get(1));
            assertEquals(selected.getFirst(), selected.get(2));
            assertEquals(2, queueLookups.get());
            assertEquals(2, publisher.metrics().snapshot().success());
            assertEquals(1, publisher.metrics().snapshot().retries());
        }
    }
}
