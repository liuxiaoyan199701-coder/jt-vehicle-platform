package io.github.jtplatform.signal.messagelog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import io.github.jtplatform.signal.delivery.ProtocolPayloadMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.t1078.T9206;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T8801;

class MessageLogEmitterTest {

    private static final Instant NOW = Instant.parse("2026-08-24T01:02:03Z");

    private final CapturingPublisher publisher = new CapturingPublisher();

    @Test
    void anUplinkFrameCarriesBothTheRawBytesAndTheParsedBody() {
        ByteBuf frame = frame("7e02000026013800138000007b0102");

        emitter(8192, 8192).inbound(null, location(), frame);

        MessageEnvelope envelope = publisher.only();
        assertEquals("device_log", envelope.type().wireValue());
        assertEquals("13800138000", envelope.deviceId());
        assertEquals(0x0200L, envelope.messageId());
        assertEquals(7, envelope.serialNo());
        Map<String, Object> payload = envelope.payload();
        assertEquals("UP", payload.get("direction"));
        assertEquals("0x0200", payload.get("msgIdHex"));
        assertEquals("7", payload.get("serialNo"));
        assertEquals("7e02000026013800138000007b0102", payload.get("rawHex"));
        assertEquals("0", payload.get("decodeError"));
        assertEquals("0", payload.get("truncated"));
        assertEquals(NOW.toString(), payload.get("logTime"));
        assertTrue(payload.get("parsedJson").toString().contains("\"speedKph\":6.0"),
                "解析结果要带得上报文正文，否则页面只剩一串 hex：" + payload.get("parsedJson"));
    }

    @Test
    void aDownlinkCommandIsRecordedInTheOppositeDirection() {
        T8801 photo = new T8801();
        photo.setMessageId(JT808.摄像头立即拍摄命令);
        photo.setClientId("13800138000");
        photo.setSerialNo(9);
        photo.setChannelId(1);
        photo.setCommand(1);

        emitter(8192, 8192).outbound(null, photo, frame("7e8801001301380013800000097e"));

        Map<String, Object> payload = publisher.only().payload();
        assertEquals("DOWN", payload.get("direction"));
        assertEquals("0x8801", payload.get("msgIdHex"));
        assertEquals("7e8801001301380013800000097e", payload.get("rawHex"));
        assertTrue(payload.get("parsedJson").toString().contains("\"channelId\":1"));
    }

    /**
     * 畸形帧走不到 decodeLog，只能靠 decode 的异常路径补捕；而它恰恰是最需要原始字节的一类。
     */
    @Test
    void aFrameThatFailsToDecodeStillLeavesItsRawBytesBehind() {
        emitter(8192, 8192).decodeFailure(
                null, frame("7edeadbeef7e"), new IndexOutOfBoundsException("readerIndex"));

        MessageEnvelope envelope = publisher.only();
        assertEquals("unknown", envelope.deviceId(), "身份不明的帧也必须留证，不能因此丢弃");
        Map<String, Object> payload = envelope.payload();
        assertEquals("UP", payload.get("direction"));
        assertEquals("1", payload.get("decodeError"));
        assertEquals("7edeadbeef7e", payload.get("rawHex"));
        assertEquals("", payload.get("parsedJson"));
        assertEquals("", payload.get("msgIdHex"));
        assertTrue(payload.get("summary").toString().contains("解码失败"));
    }

    @Test
    void overlongContentIsCutAtTheGatewaySoTheSpoolNeverSeesIt() {
        ByteBuf big = Unpooled.buffer();
        big.writeBytes(new byte[600]);

        emitter(64, 64).inbound(null, location(), big);

        Map<String, Object> payload = publisher.only().payload();
        assertEquals(64, payload.get("rawHex").toString().length());
        assertTrue(payload.get("parsedJson").toString().length() <= 64);
        assertEquals("1", payload.get("truncated"));
    }

    /** 9206 的 FTP 口令是一次性凭据，原始帧里抹不掉一段，整条 hex 都不能留。 */
    @Test
    void theOneTimeFtpCredentialNeverReachesTheLog() {
        T9206 upload = new T9206();
        upload.setMessageId(0x9206);
        upload.setClientId("13800138000");
        upload.setSerialNo(3);
        upload.setIp("10.0.0.9");
        upload.setPort(21);
        upload.setUsername("jt-upload");
        upload.setPassword("s3cr3t-one-time");
        upload.setPath("/records");
        upload.setStartTime(LocalDateTime.parse("2026-08-24T00:00:00"));
        upload.setEndTime(LocalDateTime.parse("2026-08-24T01:00:00"));

        emitter(8192, 8192).outbound(null, upload, frame("7e9206006a0138001380000003"));

        Map<String, Object> payload = publisher.only().payload();
        assertEquals("", payload.get("rawHex"));
        assertFalse(payload.toString().contains("s3cr3t-one-time"), "口令不得以任何形式进入信封");
        assertFalse(payload.toString().contains("jt-upload"));
        assertTrue(payload.get("parsedJson").toString().contains("\"password\":\"***\""));
        assertTrue(payload.get("summary").toString().contains("脱敏"));
    }

    /** 采集是可选增强：投递器抛异常也只能记一条 warn，绝不能让这一帧收发不出去。 */
    @Test
    void aFailingPublisherNeverEscapesIntoTheSignalPath() {
        MessagePublisher exploding = envelope -> {
            throw new IllegalStateException("channel down");
        };
        DeliveringMessageLogEmitter emitter = new DeliveringMessageLogEmitter(
                exploding, new ProtocolPayloadMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                "signal-test", 8192, 8192);

        emitter.inbound(null, location(), frame("7e02007e"));
        emitter.outbound(null, location(), frame("7e02007e"));
        emitter.decodeFailure(null, frame("7e02007e"), new IllegalArgumentException("bad"));
    }

    @Test
    void theNoOpEmitterAcceptsEveryCallAndDoesNothing() {
        MessageLogEmitter.NONE.inbound(null, location(), frame("7e02007e"));
        MessageLogEmitter.NONE.outbound(null, location(), frame("7e02007e"));
        MessageLogEmitter.NONE.decodeFailure(null, frame("7e02007e"), new IllegalStateException());
        assertEquals(0, publisher.envelopes.size());
    }

    private DeliveringMessageLogEmitter emitter(int maxHexChars, int maxJsonChars) {
        return new DeliveringMessageLogEmitter(publisher, new ProtocolPayloadMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), "signal-test", maxHexChars, maxJsonChars);
    }

    private static T0200 location() {
        T0200 location = new T0200();
        location.setMessageId(JT808.位置信息汇报);
        location.setClientId("13800138000");
        location.setSerialNo(7);
        location.setLatitude(31_230_000);
        location.setLongitude(121_470_000);
        location.setSpeed(60);
        location.setDeviceTime(LocalDateTime.parse("2026-08-24T09:02:03"));
        return location;
    }

    private static ByteBuf frame(String hex) {
        return Unpooled.wrappedBuffer(HexFormat.of().parseHex(hex));
    }

    private static final class CapturingPublisher implements MessagePublisher {
        private final List<MessageEnvelope> envelopes = new ArrayList<>();

        @Override
        public PublishResult publish(MessageEnvelope envelope) {
            envelopes.add(envelope);
            return PublishResult.of("test", PublishDisposition.ACCEPTED);
        }

        MessageEnvelope only() {
            assertEquals(1, envelopes.size(), "每条被记录的报文恰好发一个信封");
            return envelopes.getFirst();
        }
    }
}
