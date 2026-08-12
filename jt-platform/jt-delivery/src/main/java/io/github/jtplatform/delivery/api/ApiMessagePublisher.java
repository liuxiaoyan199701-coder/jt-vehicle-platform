package io.github.jtplatform.delivery.api;

import io.github.jtplatform.delivery.channel.AsyncChannelOptions;
import io.github.jtplatform.delivery.channel.AsyncMessageChannel;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class ApiMessagePublisher extends AsyncMessageChannel {
    public static final String CHANNEL_NAME = "api";

    private final ApiPushTransport transport;

    public ApiMessagePublisher(ApiPushTransport transport, AsyncChannelOptions options) {
        super(CHANNEL_NAME, options);
        this.transport = Objects.requireNonNull(transport, "transport");
        startRecoveredDeliveries();
    }

    @Override
    protected CompletionStage<Void> deliver(MessageEnvelope envelope) throws Exception {
        return transport.push(envelope);
    }
}
