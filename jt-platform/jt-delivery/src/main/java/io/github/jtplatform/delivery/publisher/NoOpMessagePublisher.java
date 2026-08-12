package io.github.jtplatform.delivery.publisher;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import java.util.Objects;

public final class NoOpMessagePublisher implements MessagePublisher {
    public static final String CHANNEL_NAME = "none";

    @Override
    public PublishResult publish(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return PublishResult.of(CHANNEL_NAME, PublishDisposition.DISABLED);
    }
}
