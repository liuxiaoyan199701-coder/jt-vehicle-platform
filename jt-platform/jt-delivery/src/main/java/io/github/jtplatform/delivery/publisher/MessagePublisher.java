package io.github.jtplatform.delivery.publisher;

import io.github.jtplatform.delivery.model.MessageEnvelope;

@FunctionalInterface
public interface MessagePublisher extends AutoCloseable {
    /**
     * Performs bounded, non-blocking admission only. A {@code RETRY_REQUIRED} result means the caller must retain
     * and resubmit the critical message; accepted critical messages are retried by the channel until delivered.
     */
    PublishResult publish(MessageEnvelope envelope);

    @Override
    default void close() {
    }
}
