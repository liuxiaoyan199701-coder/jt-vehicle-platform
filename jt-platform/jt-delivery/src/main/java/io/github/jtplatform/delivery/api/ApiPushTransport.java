package io.github.jtplatform.delivery.api;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ApiPushTransport {
    CompletionStage<Void> push(MessageEnvelope envelope) throws Exception;
}
