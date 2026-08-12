package io.github.jtplatform.delivery.codec;

import io.github.jtplatform.delivery.model.MessageEnvelope;

@FunctionalInterface
public interface MessageEnvelopeEncoder {
    byte[] encode(MessageEnvelope envelope);
}
