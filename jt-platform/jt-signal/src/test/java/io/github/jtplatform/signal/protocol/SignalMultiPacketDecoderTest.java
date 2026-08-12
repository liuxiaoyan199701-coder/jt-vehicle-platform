package io.github.jtplatform.signal.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import io.github.jtplatform.signal.delivery.MessageTypeClassifier;
import io.github.jtplatform.signal.delivery.ProtocolPayloadMapper;
import io.github.jtplatform.signal.delivery.SignalMessageDispatcher;
import io.github.jtplatform.signal.delivery.SignalMessageEnvelopeMapper;
import io.github.yezhihao.netmc.session.Session;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import io.github.yezhihao.protostar.SchemaManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.codec.JTMessageEncoder;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.t808.T8300;
import org.yzh.web.endpoint.JTMessagePushAdapter;

class SignalMultiPacketDecoderTest {
    @Test
    void dispatchesOnlyOnceAfterEverySubpacketArrives() {
        SchemaManager schemas = new SchemaManager("org.yzh.protocol");
        JTMessageEncoder encoder = new JTMessageEncoder(schemas);
        SignalMultiPacketDecoder decoder = new SignalMultiPacketDecoder(schemas, null);
        CapturingPublisher publisher = new CapturingPublisher();
        SignalMessageDispatcher dispatcher = new SignalMessageDispatcher(
                new SignalMessageEnvelopeMapper(
                        new ProtocolPayloadMapper(),
                        new MessageTypeClassifier(),
                        Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC),
                        "signal-test"),
                publisher);
        JTMessagePushAdapter adapter = new JTMessagePushAdapter(encoder, decoder, dispatcher);

        String content = "x".repeat(1_600);
        T8300 source = new T8300().setSign(1).setContent(content);
        source.setMessageId(JT808.文本信息下发);
        source.setClientId("123456789012");
        source.setSerialNo(17);
        ByteBuf encoded = encoder.encode(source);
        List<ByteBuf> frames = frames(encoded);
        try {
            assertEquals(2, frames.size());
            assertNull(adapter.decode(frames.get(0), (Session) null));
            assertEquals(0, publisher.messages.size());

            JTMessage decoded = adapter.decode(frames.get(1), (Session) null);
            assertEquals(content, ((T8300) decoded).getContent());
            assertEquals(1, publisher.messages.size());
        } finally {
            frames.forEach(ReferenceCountUtil::safeRelease);
            ReferenceCountUtil.safeRelease(encoded);
        }
    }

    @Test
    void independentlyReassemblesInterleavedMessagesWithDifferentSerialNumbers() {
        SchemaManager schemas = new SchemaManager("org.yzh.protocol");
        JTMessageEncoder encoder = new JTMessageEncoder(schemas);
        SignalMultiPacketDecoder decoder = new SignalMultiPacketDecoder(schemas, null);

        T8300 first = message("a".repeat(1_600), 17);
        T8300 second = message("b".repeat(1_600), 18);
        ByteBuf firstEncoded = encoder.encode(first);
        ByteBuf secondEncoded = encoder.encode(second);
        List<ByteBuf> firstFrames = frames(firstEncoded);
        List<ByteBuf> secondFrames = frames(secondEncoded);
        try {
            assertEquals(2, firstFrames.size());
            assertEquals(2, secondFrames.size());
            assertNull(decoder.decode(firstFrames.get(0)));
            assertNull(decoder.decode(secondFrames.get(0)));

            T8300 firstDecoded = (T8300) decoder.decode(firstFrames.get(1));
            T8300 secondDecoded = (T8300) decoder.decode(secondFrames.get(1));

            assertEquals(first.getContent(), firstDecoded.getContent());
            assertEquals(first.getSerialNo(), firstDecoded.getSerialNo());
            assertEquals(second.getContent(), secondDecoded.getContent());
            assertEquals(second.getSerialNo(), secondDecoded.getSerialNo());
        } finally {
            firstFrames.forEach(ReferenceCountUtil::safeRelease);
            secondFrames.forEach(ReferenceCountUtil::safeRelease);
            ReferenceCountUtil.safeRelease(firstEncoded);
            ReferenceCountUtil.safeRelease(secondEncoded);
        }
    }

    private static T8300 message(String content, int serialNo) {
        T8300 message = new T8300().setSign(1).setContent(content);
        message.setMessageId(0x8300);
        message.setClientId("123456789012");
        message.setSerialNo(serialNo);
        return message;
    }

    private static List<ByteBuf> frames(ByteBuf encoded) {
        List<ByteBuf> result = new ArrayList<>();
        int searchFrom = encoded.readerIndex();
        while (searchFrom < encoded.writerIndex()) {
            int start = encoded.indexOf(searchFrom, encoded.writerIndex(), (byte) 0x7e);
            if (start < 0) {
                break;
            }
            int end = encoded.indexOf(start + 1, encoded.writerIndex(), (byte) 0x7e);
            if (end < 0) {
                break;
            }
            result.add(encoded.retainedSlice(start, end - start + 1));
            searchFrom = end + 1;
        }
        return result;
    }

    private static final class CapturingPublisher implements MessagePublisher {
        private final List<MessageEnvelope> messages = new ArrayList<>();

        @Override
        public PublishResult publish(MessageEnvelope envelope) {
            messages.add(envelope);
            return PublishResult.of("test", PublishDisposition.ACCEPTED);
        }
    }
}
