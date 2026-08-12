package io.github.jtplatform.delivery;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import java.time.Instant;
import java.util.Map;

public final class TestEnvelopes {
    private TestEnvelopes() {
    }

    public static MessageEnvelope envelope(String deviceId, int serialNo, MessageType type) {
        return new MessageEnvelope("event-" + deviceId + '-' + serialNo, deviceId, 0x0200, serialNo,
                "JT/T 808-2019", Instant.parse("2026-08-10T00:00:00Z"), "signal-1", type,
                Map.of("sequence", serialNo, "valid", true));
    }
}
