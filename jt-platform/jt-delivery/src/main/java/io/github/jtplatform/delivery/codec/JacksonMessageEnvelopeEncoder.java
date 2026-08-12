package io.github.jtplatform.delivery.codec;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class JacksonMessageEnvelopeEncoder implements MessageEnvelopeEncoder {
    private final ObjectMapper objectMapper;

    public JacksonMessageEnvelopeEncoder() {
        this(new ObjectMapper());
    }

    public JacksonMessageEnvelopeEncoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public byte[] encode(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("eventId", envelope.eventId());
        value.put("deviceId", envelope.deviceId());
        value.put("messageId", envelope.messageId());
        value.put("serialNo", envelope.serialNo());
        value.put("protocolVersion", envelope.protocolVersion());
        value.put("receivedAt", envelope.receivedAt().toString());
        value.put("instanceId", envelope.instanceId());
        value.put("type", envelope.type().wireValue());
        value.put("payload", envelope.payload());
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("message envelope cannot be encoded as JSON", failure);
        }
    }
}
