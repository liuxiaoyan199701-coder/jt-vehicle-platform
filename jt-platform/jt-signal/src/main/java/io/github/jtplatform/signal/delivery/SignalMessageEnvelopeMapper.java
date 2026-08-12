package io.github.jtplatform.signal.delivery;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.yezhihao.netmc.session.Session;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;

public final class SignalMessageEnvelopeMapper {
    private final ProtocolPayloadMapper payloadMapper;
    private final MessageTypeClassifier typeClassifier;
    private final Clock clock;
    private final String instanceId;

    public SignalMessageEnvelopeMapper(
            ProtocolPayloadMapper payloadMapper,
            MessageTypeClassifier typeClassifier,
            Clock clock,
            String instanceId) {
        this.payloadMapper = Objects.requireNonNull(payloadMapper, "payloadMapper");
        this.typeClassifier = Objects.requireNonNull(typeClassifier, "typeClassifier");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        this.instanceId = instanceId;
    }

    public MessageEnvelope map(Session session, JTMessage message) {
        return map(session, message, Map.of());
    }

    public MessageEnvelope map(Session session, JTMessage message, Map<String, ?> additionalPayload) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(additionalPayload, "additionalPayload");
        Map<String, Object> mappedPayload = payloadMapper.map(message);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>(mappedPayload);
        additionalPayload.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("additional payload keys must not be blank");
            }
            payload.put(key, value);
        });
        return MessageEnvelope.create(
                resolveDeviceId(session, message),
                Integer.toUnsignedLong(message.getMessageId()),
                message.getSerialNo(),
                protocolVersion(message),
                clock.instant(),
                instanceId,
                typeClassifier.classify(message),
                payload);
    }

    private static String resolveDeviceId(Session session, JTMessage message) {
        DeviceDO device = null;
        if (session != null) {
            device = session.getAttribute(SessionKey.Device);
            if (device != null && hasText(device.getMobileNo())) {
                return device.getMobileNo();
            }
        }
        if (hasText(message.getClientId())) {
            return message.getClientId();
        }
        throw new IllegalArgumentException("message does not provide a canonical mobileNo/SIM identity");
    }

    private static String protocolVersion(JTMessage message) {
        if (message.isVersion() || message.getProtocolVersion() > 0) {
            return "JT/T 808-2019/" + message.getProtocolVersion();
        }
        return "JT/T 808-2013";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
