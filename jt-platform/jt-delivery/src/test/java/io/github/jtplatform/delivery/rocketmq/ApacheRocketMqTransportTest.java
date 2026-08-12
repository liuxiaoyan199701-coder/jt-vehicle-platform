package io.github.jtplatform.delivery.rocketmq;

import static io.github.jtplatform.delivery.TestEnvelopes.envelope;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jtplatform.delivery.model.MessageType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.Test;

class ApacheRocketMqTransportTest {
    @Test
    void lazilyStartsAndSendsEncodedEnvelopeToTheSelectedQueue() throws Exception {
        FakeProducer producer = new FakeProducer();
        ApacheRocketMqTransport transport = new ApacheRocketMqTransport(
                producer, ignored -> "encoded".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(3));

        assertEquals(List.of(new RocketMqQueue("broker-b", 2), new RocketMqQueue("broker-a", 1)),
                transport.availableQueues("jt-events"));
        transport.send("jt-events", new RocketMqQueue("broker-a", 1), "event-1",
                        envelope("device-1", 1, MessageType.ALARM))
                .toCompletableFuture().join();

        assertEquals(1, producer.starts.get());
        assertEquals("jt-events", producer.message.getTopic());
        assertEquals("ALARM", producer.message.getTags());
        assertEquals("event-1", producer.message.getKeys());
        assertEquals("device-1", producer.message.getUserProperty("deviceId"));
        assertArrayEquals("encoded".getBytes(StandardCharsets.UTF_8), producer.message.getBody());
        assertEquals(new MessageQueue("jt-events", "broker-a", 1), producer.queue);
        assertEquals(3_000, producer.timeoutMillis);

        transport.close();
        assertEquals(1, producer.shutdowns.get());
    }

    @Test
    void nonOkBrokerResultIsReportedAsFailure() throws Exception {
        FakeProducer producer = new FakeProducer();
        producer.status = SendStatus.FLUSH_DISK_TIMEOUT;
        try (ApacheRocketMqTransport transport = new ApacheRocketMqTransport(
                producer, ignored -> new byte[] {1}, Duration.ofSeconds(1))) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> transport.send("jt-events", new RocketMqQueue("broker-a", 0), "event-1",
                                    envelope("device-1", 1, MessageType.ALARM))
                            .toCompletableFuture().join());
            assertEquals("RocketMQ send failed: FLUSH_DISK_TIMEOUT", failure.getCause().getMessage());
        }
    }

    @Test
    void closingAnUnusedTransportDoesNotStartItAndPreventsLaterUse() {
        FakeProducer producer = new FakeProducer();
        ApacheRocketMqTransport transport = new ApacheRocketMqTransport(
                producer, ignored -> new byte[] {1}, Duration.ofSeconds(1));

        transport.close();

        assertEquals(0, producer.starts.get());
        assertEquals(0, producer.shutdowns.get());
        assertThrows(IllegalStateException.class, () -> transport.availableQueues("jt-events"));
    }

    private static final class FakeProducer implements ApacheRocketMqTransport.ProducerClient {
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger shutdowns = new AtomicInteger();
        private SendStatus status = SendStatus.SEND_OK;
        private Message message;
        private MessageQueue queue;
        private long timeoutMillis;

        @Override
        public void start() {
            starts.incrementAndGet();
        }

        @Override
        public List<MessageQueue> fetchPublishMessageQueues(String topic) {
            return List.of(new MessageQueue(topic, "broker-b", 2), new MessageQueue(topic, "broker-a", 1));
        }

        @Override
        public SendResult send(Message message, MessageQueue queue, long timeoutMillis) {
            this.message = message;
            this.queue = queue;
            this.timeoutMillis = timeoutMillis;
            return new SendResult(status, "message-1", "offset-1", queue, 0);
        }

        @Override
        public void shutdown() {
            shutdowns.incrementAndGet();
        }
    }
}
