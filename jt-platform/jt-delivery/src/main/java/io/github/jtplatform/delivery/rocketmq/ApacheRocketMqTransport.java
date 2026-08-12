package io.github.jtplatform.delivery.rocketmq;

import io.github.jtplatform.delivery.codec.MessageEnvelopeEncoder;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;

public final class ApacheRocketMqTransport implements RocketMqTransport, AutoCloseable {
    private final ProducerClient producer;
    private final MessageEnvelopeEncoder encoder;
    private final long sendTimeoutMillis;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ApacheRocketMqTransport(
            String nameServer,
            String producerGroup,
            String namespace,
            Duration sendTimeout,
            MessageEnvelopeEncoder encoder) {
        this(defaultClient(nameServer, producerGroup, namespace, sendTimeout), encoder, sendTimeout);
    }

    ApacheRocketMqTransport(ProducerClient producer, MessageEnvelopeEncoder encoder, Duration sendTimeout) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.sendTimeoutMillis = positiveMillis(sendTimeout);
    }

    @Override
    public List<RocketMqQueue> availableQueues(String topic) throws Exception {
        ensureStarted();
        return producer.fetchPublishMessageQueues(requiredText(topic, "topic")).stream()
                .map(queue -> new RocketMqQueue(queue.getBrokerName(), queue.getQueueId()))
                .toList();
    }

    @Override
    public CompletionStage<Void> send(
            String topic,
            RocketMqQueue queue,
            String messageKey,
            MessageEnvelope envelope) throws Exception {
        ensureStarted();
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(envelope, "envelope");
        String normalizedTopic = requiredText(topic, "topic");
        Message message = new Message(normalizedTopic, envelope.type().name(), encoder.encode(envelope));
        message.setKeys(requiredText(messageKey, "messageKey"));
        message.putUserProperty("deviceId", envelope.deviceId());
        message.putUserProperty("protocolVersion", envelope.protocolVersion());
        MessageQueue target = new MessageQueue(normalizedTopic, queue.brokerName(), queue.queueId());
        SendResult result;
        try {
            result = producer.send(message, target, sendTimeoutMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            String status = result == null ? "null result" : String.valueOf(result.getSendStatus());
            return CompletableFuture.failedFuture(new IllegalStateException("RocketMQ send failed: " + status));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (started) {
            if (started.get()) {
                producer.shutdown();
            }
        }
    }

    private void ensureStarted() throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("RocketMQ transport is closed");
        }
        if (started.get()) {
            return;
        }
        synchronized (started) {
            if (closed.get()) {
                throw new IllegalStateException("RocketMQ transport is closed");
            }
            if (!started.get()) {
                producer.start();
                started.set(true);
            }
        }
    }

    private static ProducerClient defaultClient(
            String nameServer,
            String producerGroup,
            String namespace,
            Duration sendTimeout) {
        DefaultMQProducer producer = new DefaultMQProducer(requiredText(producerGroup, "producerGroup"));
        producer.setNamesrvAddr(requiredText(nameServer, "nameServer"));
        if (namespace != null && !namespace.isBlank()) {
            producer.setNamespace(namespace.trim());
        }
        producer.setSendMsgTimeout(Math.toIntExact(positiveMillis(sendTimeout)));
        producer.setRetryTimesWhenSendFailed(0);
        producer.setRetryTimesWhenSendAsyncFailed(0);
        producer.setRetryAnotherBrokerWhenNotStoreOK(false);
        return new DefaultProducerClient(producer);
    }

    private static long positiveMillis(Duration duration) {
        Objects.requireNonNull(duration, "sendTimeout");
        long millis = duration.toMillis();
        if (millis < 1) {
            throw new IllegalArgumentException("sendTimeout must be at least one millisecond");
        }
        return millis;
    }

    private static String requiredText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    interface ProducerClient {
        void start() throws Exception;

        List<MessageQueue> fetchPublishMessageQueues(String topic) throws Exception;

        SendResult send(Message message, MessageQueue queue, long timeoutMillis) throws Exception;

        void shutdown();
    }

    private record DefaultProducerClient(DefaultMQProducer delegate) implements ProducerClient {
        @Override
        public void start() throws Exception {
            delegate.start();
        }

        @Override
        public List<MessageQueue> fetchPublishMessageQueues(String topic) throws Exception {
            return delegate.fetchPublishMessageQueues(topic);
        }

        @Override
        public SendResult send(Message message, MessageQueue queue, long timeoutMillis) throws Exception {
            return delegate.send(message, queue, timeoutMillis);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }
}
