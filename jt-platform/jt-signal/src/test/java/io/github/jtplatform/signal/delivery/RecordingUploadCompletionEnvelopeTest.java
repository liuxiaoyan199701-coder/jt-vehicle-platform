package io.github.jtplatform.signal.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtplatform.delivery.model.MessageType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.commons.JT1078;
import org.yzh.protocol.t1078.T1206;

class RecordingUploadCompletionEnvelopeTest {
    private final SignalMessageEnvelopeMapper mapper = new SignalMessageEnvelopeMapper(
            new ProtocolPayloadMapper(), new MessageTypeClassifier(),
            Clock.fixed(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC), "signal-test");

    @Test
    void duplicate1206ProducesSameStableEventId() {
        T1206 first = message(100, 41, 0);
        T1206 retransmission = message(101, 41, 0);

        var firstEnvelope = mapper.map(null, first);
        var secondEnvelope = mapper.map(null, retransmission);

        assertThat(firstEnvelope.eventId()).isEqualTo(secondEnvelope.eventId());
        assertThat(firstEnvelope.eventId()).isEqualTo("recording-upload-complete:device-1:41:0");
        assertThat(firstEnvelope.type()).isEqualTo(MessageType.RECORDING_METADATA);
        assertThat(firstEnvelope.payload()).containsEntry("responseSerialNo", 41).containsEntry("result", 0);
    }

    @Test
    void differentResultDoesNotCollideWithSuccessfulCompletion() {
        assertThat(mapper.map(null, message(100, 41, 0)).eventId())
                .isNotEqualTo(mapper.map(null, message(101, 41, 1)).eventId());
    }

    private static T1206 message(int serialNo, int responseSerialNo, int result) {
        T1206 message = new T1206().setResponseSerialNo(responseSerialNo).setResult(result);
        message.setMessageId(JT1078.文件上传完成通知);
        message.setClientId("device-1");
        message.setSerialNo(serialNo);
        message.setVerified(true);
        return message;
    }
}
