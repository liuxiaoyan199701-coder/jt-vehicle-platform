package io.github.jtplatform.delivery.rocketmq;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface RocketMqTransport {
    /** Returns the current queues for the topic; the publisher applies its own stable device selector. */
    List<RocketMqQueue> availableQueues(String topic) throws Exception;

    /**
     * Sends to the exact selected queue. Adapters must preserve the supplied envelope shape and configure consumers
     * for ordered consumption; retries may intentionally repeat the same message key.
     */
    CompletionStage<Void> send(
            String topic,
            RocketMqQueue queue,
            String messageKey,
            MessageEnvelope envelope) throws Exception;
}
