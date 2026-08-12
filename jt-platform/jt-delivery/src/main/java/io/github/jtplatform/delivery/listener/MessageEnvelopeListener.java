package io.github.jtplatform.delivery.listener;

import io.github.jtplatform.delivery.model.MessageEnvelope;

@FunctionalInterface
public interface MessageEnvelopeListener {
    /**
     * Observes an accepted protocol message before external delivery. Implementations must return quickly.
     */
    void onMessage(MessageEnvelope envelope);
}
