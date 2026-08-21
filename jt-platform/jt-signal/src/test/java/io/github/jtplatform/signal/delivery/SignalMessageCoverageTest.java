package io.github.jtplatform.signal.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jtplatform.delivery.listener.MessageEnvelopeListener;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import io.github.yezhihao.netmc.session.Session;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.jsatl12.DataPacket;
import org.yzh.protocol.jsatl12.T9208;
import org.yzh.protocol.t1078.T9105;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0701;
import org.yzh.protocol.t808.T0704;
import org.yzh.protocol.t808.T0801;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;

class SignalMessageCoverageTest {
    private final ProtocolPayloadMapper payloadMapper = new ProtocolPayloadMapper();
    private final MessageTypeClassifier classifier = new MessageTypeClassifier();

    @Test
    void everyProtocolMessageClassReachesThePublisher() throws Exception {
        Set<Class<? extends JTMessage>> messageClasses = protocolMessageClasses();
        assertEquals(74, messageClasses.size());

        CapturingPublisher publisher = new CapturingPublisher();
        SignalMessageDispatcher dispatcher = new SignalMessageDispatcher(
                new SignalMessageEnvelopeMapper(payloadMapper, classifier,
                        Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC), "signal-test"),
                publisher);
        Session session = authenticatedSession("123456789012");

        for (Class<? extends JTMessage> messageClass : messageClasses) {
            JTMessage message = messageClass.getDeclaredConstructor().newInstance();
            message.setMessageId(message.reflectMessageId());
            message.setClientId("123456789012");
            message.setSerialNo(1);
            if (message instanceof DataPacket packet) {
                packet.setFlag(0x30316364);
            }
            int before = publisher.messages.size();
            dispatcher.dispatch(session, message);
            assertEquals(before + 1, publisher.messages.size(), messageClass.getName());
        }

        assertEquals(74, publisher.messages.size());
    }

    @Test
    void preservesUnsignedDataFrameMessageId() {
        DataPacket packet = new DataPacket().setFlag(0x30316364).setName("alarm.bin");
        SignalMessageEnvelopeMapper mapper = new SignalMessageEnvelopeMapper(payloadMapper, classifier,
                Clock.systemUTC(), "signal-test");

        MessageEnvelope envelope = mapper.map(authenticatedSession("138000000000"), packet);

        assertEquals(0x30316364L, envelope.messageId());
        assertEquals(MessageType.ALARM, envelope.type());
    }

    @Test
    void authenticatedEnvelopeUsesTheSessionMobileNoInsteadOfTerminalId() {
        SignalMessageEnvelopeMapper mapper = new SignalMessageEnvelopeMapper(payloadMapper, classifier,
                Clock.systemUTC(), "signal-test");
        Session session = mock(Session.class);
        DeviceDO device = new DeviceDO()
                .setDeviceId("terminal-1")
                .setMobileNo("138000000001");
        when(session.<DeviceDO>getAttribute(SessionKey.Device)).thenReturn(device);
        T0200 location = new T0200();
        location.setClientId("138000000099");

        MessageEnvelope envelope = mapper.map(session, location);

        assertEquals("138000000001", envelope.deviceId());
    }

    @Test
    void unauthenticatedRegistrationUsesHeaderMobileNoBeforeTerminalId() {
        SignalMessageEnvelopeMapper mapper = new SignalMessageEnvelopeMapper(payloadMapper, classifier,
                Clock.systemUTC(), "signal-test");
        T0100 registration = new T0100()
                .setDeviceId("1380000");
        registration.setClientId("138000000000");

        MessageEnvelope envelope = mapper.map(null, registration);

        assertEquals("138000000000", envelope.deviceId());
    }

    @Test
    void rejectsTerminalIdFallbackWhenCanonicalWireIdentityIsUnavailable() {
        SignalMessageEnvelopeMapper mapper = new SignalMessageEnvelopeMapper(payloadMapper, classifier,
                Clock.systemUTC(), "signal-test");
        Session session = mock(Session.class);
        DeviceDO device = new DeviceDO().setDeviceId("terminal-session");
        when(session.<DeviceDO>getAttribute(SessionKey.Device)).thenReturn(device);
        T0100 registration = new T0100().setDeviceId("terminal-registration");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> mapper.map(session, registration));

        assertEquals("message does not provide a canonical mobileNo/SIM identity", exception.getMessage());
    }

    @Test
    void expandsLocationUnitsAndNamedFlags() {
        T0200 location = new T0200()
                .setWarnBit(1)
                .setStatusBit(3)
                .setLatitude(39_912_345)
                .setLongitude(116_397_128)
                .setSpeed(654);

        Map<String, Object> payload = payloadMapper.map(location);

        assertEquals(39.912345d, (Double) payload.get("latitude"), 0.000001d);
        assertEquals(116.397128d, (Double) payload.get("longitude"), 0.000001d);
        assertEquals(65.4f, (Float) payload.get("speedKph"), 0.001f);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) payload.get("alarmFlags")).get("emergency"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) payload.get("statusFlags")).get("accOn"));
    }

    @Test
    void removesBinaryValuesFromMultimediaAndAttachmentMessages() {
        ByteBuf bytes = Unpooled.wrappedBuffer(new byte[] {1, 2, 3});
        try {
            T0801 multimedia = new T0801().setPacket(bytes);
            T9208 attachment = new T9208().setReserves(new byte[] {4, 5, 6});

            Map<String, Object> multimediaPayload = payloadMapper.map(multimedia);
            Map<String, Object> attachmentPayload = payloadMapper.map(attachment);

            assertFalse(containsBinary(multimediaPayload));
            assertFalse(containsBinary(attachmentPayload));
            assertFalse(multimediaPayload.containsKey("packet"));
            assertFalse(attachmentPayload.containsKey("reserves"));
        } finally {
            ReferenceCountUtil.safeRelease(bytes);
        }
    }

    @Test
    void waybillCarriesRawBase64LengthAndStableSerialBasedIdempotencyKey() {
        SignalMessageEnvelopeMapper mapper = new SignalMessageEnvelopeMapper(payloadMapper, classifier,
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC), "signal-test");
        byte[] raw = "运单-A".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        T0701 first = new T0701().setData(raw);
        first.setClientId("138000000000");
        first.setMessageId(first.reflectMessageId());
        first.setSerialNo(17);
        T0701 duplicate = new T0701().setData(raw);
        duplicate.setClientId("138000000000");
        duplicate.setMessageId(duplicate.reflectMessageId());
        duplicate.setSerialNo(17);

        MessageEnvelope envelope = mapper.map(null, first);
        MessageEnvelope repeated = mapper.map(null, duplicate);

        assertEquals(MessageType.WAYBILL, envelope.type());
        assertEquals(Base64.getEncoder().encodeToString(raw), envelope.payload().get("rawBase64"));
        assertEquals(raw.length, envelope.payload().get("length"));
        assertTrue(envelope.eventId().contains(":17:"));
        assertEquals(envelope.eventId(), repeated.eventId());
        assertTrue(MessageType.WAYBILL.isCritical());
    }

    @Test
    void classifiesPreviouslyUnmappedT9105() {
        assertEquals(MessageType.MULTIMEDIA, classifier.classify(new T9105()));
    }

    @Test
    void batchLocationIsDeliveredReliablyUnlikeRealtimeLocation() {
        // 补传是那段轨迹的唯一副本，而它偏偏在通道最拥挤的时刻到达：
        // 若和实时位置一样按尽力而为投递，恰好会在这时被优先丢弃。
        T0704 batch = new T0704();
        batch.setMessageId(batch.reflectMessageId());

        assertEquals(MessageType.BATCH_LOCATION, classifier.classify(batch));
        assertTrue(MessageType.BATCH_LOCATION.isCritical());
        assertFalse(MessageType.LOCATION.isCritical());
    }

    @Test
    void localMessageListenersObserveAlarmWithoutAffectingExternalDelivery() {
        CapturingPublisher publisher = new CapturingPublisher();
        AtomicReference<MessageEnvelope> observed = new AtomicReference<>();
        MessageEnvelopeListener failing = ignored -> {
            throw new IllegalStateException("listener unavailable");
        };
        SignalMessageDispatcher dispatcher = new SignalMessageDispatcher(
                new SignalMessageEnvelopeMapper(payloadMapper, classifier, Clock.systemUTC(), "signal-test"),
                publisher,
                List.of(failing, observed::set));
        T0200 alarm = new T0200().setWarnBit(1);
        alarm.setClientId("device-alarm");
        alarm.setSerialNo(3);

        dispatcher.dispatch(null, alarm);

        assertEquals(MessageType.ALARM, observed.get().type());
        assertEquals("device-alarm", observed.get().deviceId());
        assertEquals(1, publisher.messages.size());
    }

    private static boolean containsBinary(Object value) {
        if (value instanceof byte[] || value instanceof ByteBuf || value instanceof ByteBuffer) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(SignalMessageCoverageTest::containsBinary);
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

    private static Session authenticatedSession(String mobileNo) {
        Session session = mock(Session.class);
        DeviceDO device = new DeviceDO().setDeviceId("terminal-test").setMobileNo(mobileNo);
        when(session.<DeviceDO>getAttribute(SessionKey.Device)).thenReturn(device);
        return session;
    }

    private static Set<Class<? extends JTMessage>> protocolMessageClasses() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = loader.getResources("org/yzh/protocol");
        Set<String> classNames = new LinkedHashSet<>();
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            if (resource.getProtocol().equals("file")) {
                collectFileClasses(Path.of(resource.toURI()), classNames);
            } else if (resource.getProtocol().equals("jar")) {
                collectJarClasses(resource, classNames);
            }
        }

        Set<Class<? extends JTMessage>> result = new LinkedHashSet<>();
        for (String className : classNames) {
            Class<?> type = Class.forName(className, false, loader);
            if (type != JTMessage.class
                    && JTMessage.class.isAssignableFrom(type)
                    && !Modifier.isAbstract(type.getModifiers())
                    && type.isAnnotationPresent(io.github.yezhihao.protostar.annotation.Message.class)) {
                result.add(type.asSubclass(JTMessage.class));
            }
        }
        return result;
    }

    private static void collectFileClasses(Path root, Set<String> classNames) throws IOException {
        try (var files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .forEach(path -> {
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        classNames.add("org.yzh.protocol." + relative.substring(0, relative.length() - 6)
                                .replace('/', '.'));
                    });
        }
    }

    private static void collectJarClasses(URL resource, Set<String> classNames) throws IOException {
        JarURLConnection connection = (JarURLConnection) resource.openConnection();
        try (JarFile jar = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("org/yzh/protocol/") && name.endsWith(".class") && !name.contains("$")) {
                    classNames.add(name.substring(0, name.length() - 6).replace('/', '.'));
                }
            }
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
