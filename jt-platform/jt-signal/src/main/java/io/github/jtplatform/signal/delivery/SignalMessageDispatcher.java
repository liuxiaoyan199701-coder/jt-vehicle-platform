package io.github.jtplatform.signal.delivery;

import io.github.jtplatform.delivery.listener.MessageEnvelopeListener;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import io.github.yezhihao.netmc.session.Session;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yzh.protocol.basics.JTMessage;

public final class SignalMessageDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(SignalMessageDispatcher.class);

    private final SignalMessageEnvelopeMapper mapper;
    private final MessagePublisher publisher;
    private final List<MessageEnvelopeListener> listeners;

    public SignalMessageDispatcher(SignalMessageEnvelopeMapper mapper, MessagePublisher publisher) {
        this(mapper, publisher, List.of());
    }

    public SignalMessageDispatcher(
            SignalMessageEnvelopeMapper mapper,
            MessagePublisher publisher,
            Collection<? extends MessageEnvelopeListener> listeners) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
    }

    public void dispatch(Session session, JTMessage message) {
        dispatch(session, message, Map.of());
    }

    public void dispatch(Session session, JTMessage message, Map<String, ?> additionalPayload) {
        if (message == null) {
            return;
        }
        if (!message.isVerified()) {
            LOGGER.warn("Discarding message with invalid checksum: device={}, id=0x{}",
                    message.getClientId(), Integer.toHexString(message.getMessageId()));
            return;
        }
        MessageEnvelope envelope;
        try {
            envelope = mapper.map(session, message, additionalPayload);
        } catch (RuntimeException error) {
            LOGGER.error("Unable to map terminal message: device={}, id=0x{}",
                    message.getClientId(), Integer.toHexString(message.getMessageId()), error);
            return;
        }
        notifyListeners(envelope);
        try {
            PublishResult result = publisher.publish(envelope);
            if (result.channels().containsValue(PublishDisposition.RETRY_REQUIRED)) {
                LOGGER.error("Critical message could not be retained by a delivery channel; eventId={}, type={}, result={}",
                        envelope.eventId(), envelope.type(), result.channels());
            } else if (!result.acceptedByEveryEnabledChannel()) {
                LOGGER.warn("Message delivery admission was not accepted: eventId={}, type={}, result={}",
                        envelope.eventId(), envelope.type(), result.channels());
            }
        } catch (RuntimeException error) {
            LOGGER.error("Unable to publish terminal message: device={}, id=0x{}",
                    message.getClientId(), Integer.toHexString(message.getMessageId()), error);
        }
    }

    private void notifyListeners(MessageEnvelope envelope) {
        for (MessageEnvelopeListener listener : listeners) {
            try {
                listener.onMessage(envelope);
            } catch (RuntimeException error) {
                LOGGER.error("Message listener {} failed: eventId={}, type={}",
                        listener.getClass().getName(), envelope.eventId(), envelope.type(), error);
            }
        }
    }
}
