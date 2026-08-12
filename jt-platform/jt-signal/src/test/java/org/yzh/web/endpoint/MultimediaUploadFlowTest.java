package org.yzh.web.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import io.github.jtplatform.signal.auth.DeviceAuthenticationService;
import io.github.jtplatform.signal.config.SignalProperties;
import io.github.jtplatform.signal.delivery.MessageTypeClassifier;
import io.github.jtplatform.signal.delivery.ProtocolPayloadMapper;
import io.github.jtplatform.signal.delivery.SignalMessageDispatcher;
import io.github.jtplatform.signal.delivery.SignalMessageEnvelopeMapper;
import io.github.jtplatform.signal.protocol.SignalMultiPacketDecoder;
import io.github.jtplatform.signal.session.RegistrationTokenStore;
import io.github.yezhihao.netmc.session.Session;
import io.github.yezhihao.protostar.SchemaManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.codec.JTMessageEncoder;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0801;
import org.yzh.protocol.t808.T8800;
import org.yzh.web.controller.MultimediaFileController;
import org.yzh.web.service.FileService;

class MultimediaUploadFlowTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reassemblesStoresPublishesAndDownloadsMultimediaExactlyOnce() throws Exception {
        SignalProperties properties = new SignalProperties();
        properties.getStorage().setMultimediaPath(temporaryDirectory.resolve("multimedia"));
        properties.getStorage().setAlarmAttachmentPath(temporaryDirectory.resolve("attachments"));
        FileService fileService = new FileService(properties);

        CapturingPublisher publisher = new CapturingPublisher();
        SignalMessageDispatcher dispatcher = new SignalMessageDispatcher(
                new SignalMessageEnvelopeMapper(
                        new ProtocolPayloadMapper(),
                        new MessageTypeClassifier(),
                        Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC),
                        "signal-test"),
                publisher);

        SchemaManager schemas = new SchemaManager("org.yzh.protocol");
        JTMessageEncoder encoder = new JTMessageEncoder(schemas);
        SignalMultiPacketDecoder decoder = new SignalMultiPacketDecoder(schemas, null);
        JTMessagePushAdapter adapter = new JTMessagePushAdapter(encoder, decoder, dispatcher);

        byte[] mediaBytes = new byte[1_600];
        for (int index = 0; index < mediaBytes.length; index++) {
            mediaBytes[index] = (byte) (index & 0xff);
        }
        T0801 source = multimediaMessage(mediaBytes);
        ByteBuf encoded = encoder.encode(source);
        List<ByteBuf> frames = frames(encoded);
        try {
            assertEquals(2, frames.size());
            assertNull(adapter.decode(frames.get(0), (Session) null));
            assertEquals(0, publisher.messages.size());

            T0801 decoded = assertInstanceOf(T0801.class, adapter.decode(frames.get(1), (Session) null));
            assertEquals(0, publisher.messages.size(), "T0801 must not be published before durable storage");

            JT808Endpoint endpoint = new JT808Endpoint(
                    fileService,
                    new RegistrationTokenStore(),
                    org.mockito.Mockito.mock(DeviceAuthenticationService.class),
                    dispatcher);
            assertInstanceOf(T8800.class, endpoint.T0801(decoded, null));
            assertEquals(1, publisher.messages.size());

            MessageEnvelope envelope = publisher.messages.getFirst();
            Map<String, Object> payload = envelope.payload();
            assertEquals(77L, payload.get("fileId"));
            assertEquals("image", payload.get("fileType"));
            assertEquals("jpeg", payload.get("fileFormat"));
            assertEquals(1_600L, payload.get("size"));
            assertFalse(containsBinary(payload));

            Map<?, ?> relatedAlarm = assertInstanceOf(Map.class, payload.get("relatedAlarm"));
            Map<?, ?> location = assertInstanceOf(Map.class, relatedAlarm.get("location"));
            Map<?, ?> alarmFlags = assertInstanceOf(Map.class, location.get("alarmFlags"));
            assertEquals(Boolean.TRUE, alarmFlags.get("emergency"));

            String accessAddress = (String) payload.get("accessAddress");
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MultimediaFileController(fileService)).build();
            mockMvc.perform(get(accessAddress))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(mediaBytes));

            T0801 duplicate = multimediaMessage(mediaBytes);
            assertInstanceOf(T8800.class, endpoint.T0801(duplicate, null));
            assertEquals(1, publisher.messages.size(), "a retransmitted stored file must not be published twice");
            assertEquals(1, countRegularFiles(temporaryDirectory.resolve("multimedia")));
        } finally {
            frames.forEach(ReferenceCountUtil::safeRelease);
            ReferenceCountUtil.safeRelease(encoded);
            ReferenceCountUtil.safeRelease(source.getPacket());
        }
    }

    @Test
    void rejectsPathTraversalOutsideTheMultimediaRoot() {
        SignalProperties properties = new SignalProperties();
        properties.getStorage().setMultimediaPath(temporaryDirectory.resolve("multimedia"));
        FileService fileService = new FileService(properties);

        assertThrows(IllegalArgumentException.class, () -> fileService.resolveMediaFile("..", "secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> fileService.resolveMediaFile("device-1", "../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> fileService.resolveMediaFile("device-1", "a\\secret.txt"));
    }

    private static T0801 multimediaMessage(byte[] bytes) {
        T0200 location = new T0200()
                .setWarnBit(1)
                .setStatusBit(3)
                .setLatitude(39_912_345)
                .setLongitude(116_397_128)
                .setSpeed(654)
                .setDeviceTime(LocalDateTime.of(2026, 8, 10, 12, 34, 56));
        T0801 message = new T0801()
                .setId(77)
                .setType(0)
                .setFormat(0)
                .setEvent(2)
                .setChannelId(1)
                .setLocation(location)
                .setPacket(Unpooled.wrappedBuffer(bytes));
        message.setMessageId(JT808.多媒体数据上传);
        message.setClientId("123456789012");
        message.setSerialNo(17);
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

    private static boolean containsBinary(Object value) {
        if (value instanceof byte[] || value instanceof ByteBuf || value instanceof ByteBuffer) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(MultimediaUploadFlowTest::containsBinary);
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsBinary(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long countRegularFiles(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
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
