package io.github.jtplatform.delivery.rocketmq;

import io.github.jtplatform.delivery.channel.AsyncChannelOptions;
import io.github.jtplatform.delivery.channel.AsyncMessageChannel;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public final class RocketMqMessagePublisher extends AsyncMessageChannel {
    public static final String CHANNEL_NAME = "rocketmq";

    private final String topic;
    private final RocketMqTransport transport;
    private final DeviceQueueSelector queueSelector;
    private final boolean closeTransport;
    private final ConcurrentHashMap<String, RocketMqQueue> selectedQueues = new ConcurrentHashMap<>();

    public RocketMqMessagePublisher(
            String topic,
            RocketMqTransport transport,
            DeviceQueueSelector queueSelector,
            AsyncChannelOptions options) {
        this(topic, transport, queueSelector, options, false);
    }

    public RocketMqMessagePublisher(
            String topic,
            RocketMqTransport transport,
            DeviceQueueSelector queueSelector,
            AsyncChannelOptions options,
            boolean closeTransport) {
        super(CHANNEL_NAME, options);
        this.topic = requireText(topic, "topic");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.queueSelector = Objects.requireNonNull(queueSelector, "queueSelector");
        this.closeTransport = closeTransport;
        startRecoveredDeliveries();
    }

    @Override
    protected CompletionStage<Void> deliver(MessageEnvelope envelope) throws Exception {
        RocketMqQueue queue = selectedQueues.get(envelope.eventId());
        if (queue == null) {
            List<RocketMqQueue> availableQueues = transport.availableQueues(topic);
            RocketMqQueue selected = queueSelector.select(envelope.deviceId(), availableQueues);
            RocketMqQueue existing = selectedQueues.putIfAbsent(envelope.eventId(), selected);
            queue = existing == null ? selected : existing;
        }
        return transport.send(topic, queue, envelope.eventId(), envelope);
    }

    @Override
    protected void deliveryTerminated(MessageEnvelope envelope) {
        selectedQueues.remove(envelope.eventId());
    }

    @Override
    protected void channelClosed() {
        if (!closeTransport || !(transport instanceof AutoCloseable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to close RocketMQ transport", exception);
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
