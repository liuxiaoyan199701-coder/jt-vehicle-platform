package io.github.jtplatform.delivery.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.delivery.TestEnvelopes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageEnvelopeTest {
    @Test
    @SuppressWarnings("unchecked")
    void freezesExpandedPayloadDeeply() {
        List<Object> alarms = new ArrayList<>(List.of("overspeed"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("longitude", 116.397);
        payload.put("alarms", alarms);

        MessageEnvelope envelope = new MessageEnvelope("event-1", " device-1 ", 0x0200, 7,
                "808-2019", Instant.parse("2026-08-10T00:00:00Z"), " signal-1 ", MessageType.ALARM, payload);
        alarms.add("fatigue");
        payload.put("latitude", 39.908);

        assertEquals("device-1", envelope.deviceId());
        assertEquals("signal-1", envelope.instanceId());
        assertEquals(Map.of("longitude", 116.397, "alarms", List.of("overspeed")), envelope.payload());
        assertThrows(UnsupportedOperationException.class, () -> envelope.payload().put("x", 1));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) envelope.payload().get("alarms")).add("x"));
    }

    @Test
    void rejectsBinaryAndOpaquePayloadValues() {
        assertThrows(IllegalArgumentException.class, () -> new MessageEnvelope("event-1", "device-1", 1, 1,
                "808", Instant.now(), "signal-1", MessageType.MULTIMEDIA, Map.of("content", new byte[1])));
        assertThrows(IllegalArgumentException.class, () -> new MessageEnvelope("event-1", "device-1", 1, 1,
                "808", Instant.now(), "signal-1", MessageType.OTHER, Map.of("dto", new Object())));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("alarm", true);
        nested.put(" alarm ", false);
        assertThrows(IllegalArgumentException.class, () -> new MessageEnvelope("event-1", "device-1", 1, 1,
                "808", Instant.now(), "signal-1", MessageType.ALARM, Map.of("flags", nested)));
    }

    @Test
    void reliabilityDefaultsUnknownSemanticMessagesToCritical() {
        assertTrue(MessageType.REGISTER.isCritical());
        assertTrue(MessageType.AUTHENTICATION.isCritical());
        assertTrue(MessageType.ALARM.isCritical());
        assertTrue(MessageType.MULTIMEDIA.isCritical());
        assertTrue(MessageType.OTHER.isCritical());
        assertEquals(DeliveryReliability.BEST_EFFORT, MessageType.LOCATION.reliability());
        assertEquals(0x0200, TestEnvelopes.envelope("device-1", 1, MessageType.LOCATION).messageId());
    }

    @Test
    void preservesUnsigned32BitExtensionMessageIds() {
        MessageEnvelope envelope = new MessageEnvelope("event-1", "device-1", 0x3031_6364L, 1,
                "808", Instant.now(), "signal-1", MessageType.ALARM, Map.of());

        assertEquals(0x3031_6364L, envelope.messageId());
        assertThrows(IllegalArgumentException.class, () -> new MessageEnvelope("event-2", "device-1",
                0x1_0000_0000L, 1, "808", Instant.now(), "signal-1", MessageType.ALARM, Map.of()));
    }
}
