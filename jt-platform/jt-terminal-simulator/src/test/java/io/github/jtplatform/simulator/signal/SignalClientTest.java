package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.Jt808Version;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT1078;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.t1078.T9101;
import org.yzh.protocol.t1078.T9102;
import org.yzh.protocol.t808.T0001;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0102;
import org.yzh.protocol.t808.T8100;

class SignalClientTest {
    private static final SignalClient.Timing FAST_TIMING = new SignalClient.Timing(
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(300));

    @ParameterizedTest
    @EnumSource(Jt808Version.class)
    void authenticatesBothVersionsAndExplicitlyAnswersCommands(Jt808Version version) throws Exception {
        RecordingCommandHandler commands = new RecordingCommandHandler();
        RecordingListener listener = new RecordingListener();
        try (FakeSignalServer server = new FakeSignalServer(peer -> {
            JTMessage registrationMessage = peer.read();
            T0100 registration = assertInstanceOf(T0100.class, registrationMessage);
            assertEquals(version.protocolVersion(), registration.getProtocolVersion());
            assertEquals(version.versionFlag(), registration.isVersion());
            assertEquals(version.mobileNumberLength(), registration.getClientId().length());
            assertEquals("1380000", registration.getDeviceId());
            peer.write(registrationResponse(registration, "server-issued-token"));

            JTMessage authenticationMessage = peer.read();
            T0102 authentication = assertInstanceOf(T0102.class, authenticationMessage);
            assertEquals("server-issued-token", authentication.getToken());
            if (version == Jt808Version.V2019) {
                assertEquals("000000000000000", authentication.getImei());
                assertEquals("0.1.0", authentication.getSoftwareVersion());
            }
            peer.write(success(authentication));

            JTMessage heartbeat = peer.readUntil(message -> message.getMessageId() == JT808.终端心跳);
            assertTrue(heartbeat.isVerified());

            T9101 open = platform(new T9101()
                    .setIp("127.0.0.1")
                    .setTcpPort(7_811)
                    .setUdpPort(0)
                    .setChannelNo(1)
                    .setMediaType(1)
                    .setStreamType(0), authentication, 500);
            peer.write(open);
            assertGeneralResponse(peer.readUntil(message -> message instanceof T0001),
                    open, T0001.Success);

            T9102 control = platform(new T9102()
                    .setChannelNo(1)
                    .setCommand(2)
                    .setCloseType(0)
                    .setStreamType(0), authentication, 501);
            peer.write(control);
            assertGeneralResponse(peer.readUntil(message -> message instanceof T0001),
                    control, T0001.Success);

            JTMessage unknown = platform(new JTMessage(), authentication, 502);
            unknown.setMessageId(0x8f01);
            peer.write(unknown);
            assertGeneralResponse(peer.readUntil(message -> message instanceof T0001),
                    unknown, T0001.NotSupport);

            T9102 corrupt = platform(new T9102()
                    .setChannelNo(1)
                    .setCommand(0)
                    .setCloseType(0)
                    .setStreamType(0), authentication, 503);
            peer.writeBytes(corruptBodyWithoutUpdatingChecksum(peer.codec.encode(corrupt)));
            assertGeneralResponse(peer.readUntil(message -> message instanceof T0001),
                    corrupt, T0001.MessageError);
        })) {
            SimulatorConfig config = config(version, server.port());
            try (SignalClient client = new SignalClient(config, commands, listener, FAST_TIMING, () -> 0.5d)) {
                client.connect();
                server.await(Duration.ofSeconds(5));
                assertTrue(listener.online.await(2, TimeUnit.SECONDS));
                assertEquals(1, commands.opens.get());
                assertEquals(1, commands.controls.get());
                assertEquals(SignalState.ONLINE, client.state());
            }
        }
    }

    @Test
    void rejectsMismatchedRegistrationCorrelation() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (FakeSignalServer server = new FakeSignalServer(peer -> {
            T0100 registration = assertInstanceOf(T0100.class, peer.read());
            T8100 response = registrationResponse(registration, "token");
            response.setResponseSerialNo(registration.getSerialNo() + 1);
            peer.write(response);
        })) {
            SignalClient.Timing timing = new SignalClient.Timing(
                    Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1),
                    Duration.ofSeconds(2), Duration.ofSeconds(2));
            try (SignalClient client = new SignalClient(
                    config(Jt808Version.V2013, server.port()),
                    new RecordingCommandHandler(), listener, timing, () -> 0.5d)) {
                client.connect();
                server.await(Duration.ofSeconds(3));
                assertTrue(listener.error.await(2, TimeUnit.SECONDS));
                assertFalse(listener.states.contains(SignalState.ONLINE));
            }
        }
    }

    @Test
    void reconnectsAndRegistersAgainAfterTheSignalSocketCloses() throws Exception {
        RecordingCommandHandler commands = new RecordingCommandHandler();
        RecordingListener listener = new RecordingListener(2);
        Consumer<FakePeer> authenticateAndClose = peer -> {
            authenticate(peer);
            peer.close();
        };
        Consumer<FakePeer> authenticateAndWait = peer -> {
            authenticate(peer);
            peer.readUntil(message -> message.getMessageId() == JT808.终端心跳);
        };

        try (FakeSignalServer server = new FakeSignalServer(authenticateAndClose, authenticateAndWait);
                SignalClient client = new SignalClient(
                        config(Jt808Version.V2013, server.port()), commands, listener,
                        FAST_TIMING, () -> 0.5d)) {
            client.connect();

            assertTrue(listener.online.await(5, TimeUnit.SECONDS));
            server.await(Duration.ofSeconds(5));
            assertTrue(commands.disconnects.get() >= 1);
            assertTrue(listener.states.contains(SignalState.RECONNECT_WAIT));
            assertEquals(SignalState.ONLINE, client.state());
        }
    }

    @Test
    void activeDisconnectCleansMediaWithoutReportingAnInterruptedFailure() throws Exception {
        RecordingCommandHandler commands = new RecordingCommandHandler();
        RecordingListener listener = new RecordingListener();
        try (FakeSignalServer server = new FakeSignalServer(peer -> {
            authenticate(peer);
            try {
                peer.read();
            } catch (RuntimeException ignored) {
                // The client closes the socket during the active disconnect under test.
            }
        }); SignalClient client = new SignalClient(
                config(Jt808Version.V2013, server.port()), commands, listener,
                FAST_TIMING, () -> 0.5d)) {
            client.connect();
            assertTrue(listener.online.await(3, TimeUnit.SECONDS));

            client.disconnect();

            assertTrue(awaitCondition(() -> commands.disconnects.get() == 1, Duration.ofSeconds(2)));
            assertTrue(awaitCondition(
                    () -> client.state() == SignalState.DISCONNECTED, Duration.ofSeconds(2)));
            assertTrue(listener.errors.stream().noneMatch(
                    context -> context.contains("cleaning media after signal disconnect")));
        }
    }

    @Test
    void resetsReconnectBackoffAfterAuthenticationSucceeds() throws Exception {
        RecordingCommandHandler commands = new RecordingCommandHandler();
        RecordingListener listener = new RecordingListener(1, 2);
        Consumer<FakePeer> failBeforeAuthentication = peer -> {
            T0100 registration = assertInstanceOf(T0100.class, peer.read());
            T8100 response = registrationResponse(registration, "token");
            response.setResponseSerialNo(registration.getSerialNo() + 1);
            peer.write(response);
        };
        Consumer<FakePeer> authenticateAndClose = peer -> {
            authenticate(peer);
            peer.close();
        };

        try (FakeSignalServer server = new FakeSignalServer(failBeforeAuthentication, authenticateAndClose);
                SignalClient client = new SignalClient(
                        config(Jt808Version.V2013, server.port()), commands, listener,
                        FAST_TIMING, () -> 0.5d)) {
            client.connect();

            server.await(Duration.ofSeconds(5));
            assertTrue(listener.reconnectWaits.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("Reconnect in 100 ms", "Reconnect in 100 ms"),
                    List.copyOf(listener.reconnectDetails).subList(0, 2));
        }
    }

    @Test
    void productionTimingUsesThirtySecondHeartbeatsAndOneToThirtySecondBackoff() {
        SignalClient.Timing timing = SignalClient.Timing.defaults();

        assertEquals(Duration.ofSeconds(30), timing.heartbeatInterval());
        assertEquals(Duration.ofSeconds(1), timing.backoffInitial());
        assertEquals(Duration.ofSeconds(30), timing.backoffMaximum());
    }

    private static void authenticate(FakePeer peer) {
        T0100 registration = assertInstanceOf(T0100.class, peer.read());
        peer.write(registrationResponse(registration, "token"));
        T0102 authentication = assertInstanceOf(T0102.class, peer.read());
        peer.write(success(authentication));
    }

    private static SimulatorConfig config(Jt808Version version, int port) {
        SimulatorConfig source = SimulatorConfig.defaults();
        String mobile = version == Jt808Version.V2019 ? "12345678901234567890" : "123456789012";
        return new SimulatorConfig(
                "127.0.0.1", port, version, mobile, "1380000", 1,
                source.registration(), source.ffmpegPath(), source.cameraName(), source.microphoneName(),
                source.mainProfile(), source.subProfile(), source.previewWidth(), source.previewHeight(),
                source.previewFps(), source.maxPayloadBytes());
    }

    private static T8100 registrationResponse(T0100 request, String token) {
        return platform(new T8100()
                .setResponseSerialNo(request.getSerialNo())
                .setResultCode(T8100.Success)
                .setToken(token), request, 100);
    }

    private static boolean awaitCondition(
            java.util.function.BooleanSupplier condition,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static T0001 success(T0102 request) {
        T0001 response = new T0001()
                .setResponseSerialNo(request.getSerialNo())
                .setResponseMessageId(JT808.终端鉴权)
                .setResultCode(T0001.Success);
        response.setMessageId(JT808.平台通用应答);
        return platform(response, request, 101);
    }

    private static <T extends JTMessage> T platform(T message, JTMessage request, int serial) {
        if (message.getMessageId() == 0) {
            message.setMessageId(message.reflectMessageId());
        }
        message.setClientId(request.getClientId());
        message.setProtocolVersion(request.getProtocolVersion());
        message.setVersion(request.isVersion());
        message.setSerialNo(serial);
        return message;
    }

    private static void assertGeneralResponse(JTMessage message, JTMessage request, int result) {
        T0001 response = assertInstanceOf(T0001.class, message);
        assertEquals(JT808.终端通用应答, response.getMessageId());
        assertEquals(request.getSerialNo(), response.getResponseSerialNo());
        assertEquals(request.getMessageId(), response.getResponseMessageId());
        assertEquals(result, response.getResultCode());
        assertTrue(response.isVerified());
    }

    private static byte[] corruptBodyWithoutUpdatingChecksum(byte[] encoded) {
        byte[] corrupt = encoded.clone();
        for (int index = corrupt.length - 3; index > 4; index--) {
            int value = corrupt[index] & 0xff;
            if (corrupt[index - 1] != 0x7d && value != 0x7d && value != 0x7e) {
                corrupt[index] ^= 0x10;
                return corrupt;
            }
        }
        throw new IllegalStateException("Unable to find a safe encoded body byte to corrupt");
    }

    private static final class RecordingCommandHandler implements SignalCommandHandler {
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger controls = new AtomicInteger();
        private final AtomicInteger disconnects = new AtomicInteger();

        @Override
        public CompletionStage<Integer> open(T9101 command) {
            opens.incrementAndGet();
            return CompletableFuture.completedFuture(T0001.Success);
        }

        @Override
        public CompletionStage<Integer> control(T9102 command) {
            controls.incrementAndGet();
            return CompletableFuture.completedFuture(T0001.Success);
        }

        @Override
        public void onSignalDisconnected() {
            disconnects.incrementAndGet();
        }
    }

    private static final class RecordingListener implements SignalListener {
        private final CountDownLatch online;
        private final CountDownLatch reconnectWaits;
        private final CountDownLatch error = new CountDownLatch(1);
        private final List<SignalState> states = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> reconnectDetails = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        private RecordingListener() {
            this(1, 0);
        }

        private RecordingListener(int onlineTransitions) {
            this(onlineTransitions, 0);
        }

        private RecordingListener(int onlineTransitions, int reconnectTransitions) {
            this.online = new CountDownLatch(onlineTransitions);
            this.reconnectWaits = new CountDownLatch(reconnectTransitions);
        }

        @Override
        public void onStateChanged(SignalState previous, SignalState current, String detail) {
            states.add(current);
            if (current == SignalState.ONLINE) {
                online.countDown();
            }
            if (current == SignalState.RECONNECT_WAIT) {
                reconnectDetails.add(detail);
                reconnectWaits.countDown();
            }
        }

        @Override
        public void onError(String context, Throwable error) {
            errors.add(context);
            this.error.countDown();
        }
    }

    private static final class FakeSignalServer implements AutoCloseable {
        private final ServerSocket server;
        private final List<Consumer<FakePeer>> scenarios;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private final Thread thread;

        @SafeVarargs
        private FakeSignalServer(Consumer<FakePeer>... scenarios) throws IOException {
            this.scenarios = List.of(scenarios);
            this.server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            this.thread = Thread.ofVirtual().name("fake-signal-server").start(this::run);
        }

        private int port() {
            return server.getLocalPort();
        }

        private void await(Duration timeout) throws Exception {
            completed.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void run() {
            try {
                for (Consumer<FakePeer> scenario : scenarios) {
                    try (Socket socket = server.accept(); FakePeer peer = new FakePeer(socket)) {
                        scenario.accept(peer);
                    }
                }
                completed.complete(null);
            } catch (Throwable failure) {
                if (server.isClosed() && failure instanceof IOException) {
                    completed.complete(null);
                } else {
                    completed.completeExceptionally(failure);
                }
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.interrupt();
            thread.join(Duration.ofSeconds(2));
        }
    }

    private static final class FakePeer implements AutoCloseable {
        private final Socket socket;
        private final Jt808MessageCodec codec = new Jt808MessageCodec();
        private final Jt808FrameReader reader;
        private final Jt808MessageWriter writer;

        private FakePeer(Socket socket) throws IOException {
            this.socket = Objects.requireNonNull(socket, "socket");
            socket.setSoTimeout(3_000);
            this.reader = new Jt808FrameReader(socket.getInputStream());
            this.writer = new Jt808MessageWriter(socket.getOutputStream(), codec);
        }

        private JTMessage read() {
            try {
                return codec.decode(reader.readFrame());
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        }

        private JTMessage readUntil(java.util.function.Predicate<JTMessage> predicate) {
            while (true) {
                JTMessage message = read();
                if (predicate.test(message)) {
                    return message;
                }
            }
        }

        private void write(JTMessage message) {
            try {
                writer.write(message);
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        }

        private void writeBytes(byte[] frame) {
            try {
                socket.getOutputStream().write(frame);
                socket.getOutputStream().flush();
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
