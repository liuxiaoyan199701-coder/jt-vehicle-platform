package io.github.jtplatform.delivery.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.delivery.TestEnvelopes;
import io.github.jtplatform.delivery.model.MessageType;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JacksonMessageEnvelopeEncoderTest {
    @Test
    void emitsTheUnifiedWireShape() {
        String json = new String(new JacksonMessageEnvelopeEncoder().encode(
                TestEnvelopes.envelope("device-1", 7, MessageType.ALARM)), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"eventId\":\"event-device-1-7\""));
        assertTrue(json.contains("\"deviceId\":\"device-1\""));
        assertTrue(json.contains("\"messageId\":512"));
        assertTrue(json.contains("\"serialNo\":7"));
        assertTrue(json.contains("\"protocolVersion\":\"JT/T 808-2019\""));
        assertTrue(json.contains("\"receivedAt\":\"2026-08-10T00:00:00Z\""));
        assertTrue(json.contains("\"instanceId\":\"signal-1\""));
        assertTrue(json.contains("\"type\":\"alarm\""));
        assertTrue(json.contains("\"payload\":{"));
        assertEquals('{', json.charAt(0));
    }
}
