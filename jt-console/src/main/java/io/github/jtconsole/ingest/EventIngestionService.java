package io.github.jtconsole.ingest;

import io.github.jtconsole.repository.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies idempotency and business projections as one atomic database operation. */
@Service
public class EventIngestionService {

    private final EventRepository events;
    private final LocationService locations;

    public EventIngestionService(EventRepository events, LocationService locations) {
        this.events = events;
        this.locations = locations;
    }

    @Transactional
    public IngestionResult ingest(MessageEnvelope envelope) {
        validate(envelope);
        MessageEnvelope normalized = normalize(envelope);
        if (!events.markProcessed(normalized.eventId())) {
            return IngestionResult.duplicate();
        }

        LocationHandlingResult handled = locations.handle(normalized);
        return IngestionResult.committed(handled);
    }

    private static void validate(MessageEnvelope envelope) {
        if (envelope == null) {
            throw new InvalidEnvelopeException("request body must not be empty");
        }
        if (envelope.eventId() == null || envelope.eventId().isBlank()) {
            throw new InvalidEnvelopeException("eventId must not be blank");
        }
        if (envelope.deviceId() == null || envelope.deviceId().isBlank()) {
            throw new InvalidEnvelopeException("deviceId must not be blank");
        }
        if (envelope.messageId() == null) {
            throw new InvalidEnvelopeException("messageId must not be null");
        }
    }

    private static MessageEnvelope normalize(MessageEnvelope envelope) {
        return new MessageEnvelope(
                envelope.eventId().trim(),
                envelope.deviceId().trim(),
                envelope.messageId(),
                envelope.serialNo(),
                envelope.protocolVersion(),
                envelope.receivedAt(),
                envelope.instanceId(),
                envelope.type(),
                envelope.payload());
    }
}
