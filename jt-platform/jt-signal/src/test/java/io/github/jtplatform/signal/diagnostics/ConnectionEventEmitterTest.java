package io.github.jtplatform.signal.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.yzh.protocol.t808.T8100.AlreadyRegisteredTerminal;
import static org.yzh.protocol.t808.T8100.AlreadyRegisteredVehicle;
import static org.yzh.protocol.t808.T8100.NotFoundTerminal;
import static org.yzh.protocol.t808.T8100.NotFoundVehicle;
import static org.yzh.protocol.t808.T8100.Success;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConnectionEventEmitterTest {
    @ParameterizedTest
    @CsvSource({
        Success + ",注册成功",
        AlreadyRegisteredVehicle + ",车辆已被注册",
        NotFoundVehicle + ",数据库中无该车辆",
        AlreadyRegisteredTerminal + ",终端已被注册",
        NotFoundTerminal + ",数据库中无该终端"
    })
    void emitsEachRegistrationResultCode(int code, String reason) {
        CapturingPublisher publisher = new CapturingPublisher();
        ConnectionEventEmitter emitter = new ConnectionEventEmitter(
                publisher, Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC), "signal-1");

        emitter.registerResult("device-" + code, code, reason, "127.0.0.1:1");

        assertEquals(1, publisher.events.size());
        MessageEnvelope event = publisher.events.getFirst();
        assertEquals("REGISTER_RESULT", event.payload().get("kind"));
        assertEquals(code, event.payload().get("reasonCode"));
        assertEquals(reason, event.payload().get("reason"));
    }

    @Test
    void emitsConnectionEventWithSyntheticIdAndConnectionType() {
        CapturingPublisher publisher = new CapturingPublisher();
        ConnectionEventEmitter emitter = new ConnectionEventEmitter(
                publisher, Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC), "signal-1");

        emitter.registerResult("device-1", 4, "数据库中无该终端", "127.0.0.1:1");

        MessageEnvelope event = publisher.events.getFirst();
        assertEquals(0x10001L, event.messageId());
        assertEquals("connection", event.type().wireValue());
        assertEquals("REGISTER_RESULT", event.payload().get("kind"));
        assertEquals(4, event.payload().get("reasonCode"));
    }

    @Test
    void mergesSameReasonDuringWindowAndEmitsAccumulatedCountAfterWindow() {
        CapturingPublisher publisher = new CapturingPublisher();
        MutableClock clock = new MutableClock();
        ConnectionEventEmitter emitter = new ConnectionEventEmitter(publisher, clock, "signal-1");

        emitter.disconnected("device-1", null, null, "对端断开");
        emitter.disconnected("device-1", null, null, "对端断开");
        assertEquals(1, publisher.events.size());
        clock.advance(Duration.ofSeconds(61));
        emitter.disconnected("device-1", null, null, "对端断开");

        assertEquals(2, publisher.events.size());
        assertEquals(3, publisher.events.getLast().payload().get("repeatCount"));

        clock.advance(Duration.ofSeconds(60));
        emitter.disconnected("device-1", null, null, "对端断开");
        assertEquals(3, publisher.events.size());
        assertEquals(2, publisher.events.getLast().payload().get("repeatCount"));
    }

    @Test
    void limitsProtocolErrorsPerDevicePerHour() {
        CapturingPublisher publisher = new CapturingPublisher();
        ConnectionEventEmitter emitter = new ConnectionEventEmitter(
                publisher, Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC), "signal-1");
        for (int i = 0; i < 100; i++) {
            emitter.protocolError("device-1", "bad-frame", null);
        }
        assertTrue(publisher.events.size() <= 60);
    }

    private static final class CapturingPublisher implements MessagePublisher {
        private final List<MessageEnvelope> events = new ArrayList<>();

        @Override
        public PublishResult publish(MessageEnvelope envelope) {
            events.add(envelope);
            return PublishResult.of("test", PublishDisposition.ACCEPTED);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-08-21T00:00:00Z");

        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
