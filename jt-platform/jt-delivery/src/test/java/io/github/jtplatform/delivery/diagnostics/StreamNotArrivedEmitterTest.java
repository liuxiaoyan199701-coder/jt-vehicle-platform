package io.github.jtplatform.delivery.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamNotArrivedEmitterTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-23T01:02:03Z"), ZoneOffset.UTC);

    @Test
    void carriesTheStreamIdentityAndWaitedTimeAsAConnectionEvent() {
        CapturingPublisher publisher = new CapturingPublisher();
        StreamNotArrivedEmitter emitter = new StreamNotArrivedEmitter(publisher, CLOCK, "api-1");

        emitter.onStreamNotArrived(new StreamKey("device-1", 3, StreamKind.SUB), "media-2", 30_000);

        assertThat(publisher.events).hasSize(1);
        MessageEnvelope event = publisher.events.getFirst();
        assertThat(event.deviceId()).isEqualTo("device-1");
        assertThat(event.type().wireValue()).isEqualTo("connection");
        assertThat(event.messageId()).isEqualTo(0x10002L);
        assertThat(event.payload()).containsEntry("kind", "STREAM_NOT_ARRIVED")
                .containsEntry("repeatCount", 1)
                .containsEntry("eventTime", "2026-08-23T01:02:03Z");
        assertThat(event.payload().get("detail")).isEqualTo(Map.of(
                "channel", 3, "streamKind", "sub", "waitedMs", 30_000L, "mediaInstanceId", "media-2"));
    }

    @Test
    void publishFailureIsSwallowedSoStreamReclaimIsNeverBlocked() {
        StreamNotArrivedEmitter emitter = new StreamNotArrivedEmitter(envelope -> {
            throw new IllegalStateException("broker down");
        }, CLOCK, "api-1");

        emitter.onStreamNotArrived(new StreamKey("device-1", 1, StreamKind.MAIN), null, 5_000);
    }

    private static final class CapturingPublisher implements MessagePublisher {
        private final List<MessageEnvelope> events = new ArrayList<>();

        @Override
        public PublishResult publish(MessageEnvelope envelope) {
            events.add(envelope);
            return PublishResult.of("test", PublishDisposition.ACCEPTED);
        }
    }
}
