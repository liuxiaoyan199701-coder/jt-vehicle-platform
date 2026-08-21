package io.github.jtplatform.simulator.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.config.Jt808Version;
import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.config.TerminalManagementConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.LocalDateTime;
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
import org.yzh.protocol.commons.transform.AttributeKey;
import org.yzh.protocol.t1078.T9101;
import org.yzh.protocol.t1078.T9102;
import org.yzh.protocol.t808.T0001;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0102;
import org.yzh.protocol.t808.T0104;
import org.yzh.protocol.t808.T0108;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0201_0500;
import org.yzh.protocol.t808.T8100;
import org.yzh.protocol.t808.T8103;
import org.yzh.protocol.t808.T8105;
import org.yzh.protocol.t808.T8108;
import org.yzh.protocol.t808.T8300;

class SignalClientTest {
    private static final SignalClient.Timing FAST_TIMING = new SignalClient.Timing(
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            Duration.ofMillis(50),
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
            try (SignalClient client = new SignalClient(config, commands, listener, LocationSource.NONE, FAST_TIMING, () -> 0.5d)) {
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
    void storesParametersAnswersQueriesAndReconnectsAfterReset() throws Exception {
        RecordingListener listener = new RecordingListener(2);
        Consumer<FakePeer> manageAndReset = peer -> {
            authenticate(peer);
            JTMessage authenticated = peer.readUntil(message -> message.getMessageId() == JT808.终端心跳);

            T8103 set = platform(new T8103().addParameter(0x0001, 1L)
                    .addParameter(0x0055, 95L), authenticated, 600);
            peer.write(set);
            assertGeneralResponse(peer.readUntil(message -> message instanceof T0001),
                    set, T0001.Success);

            JTMessage query = platform(new JTMessage(), authenticated, 601);
            query.setMessageId(JT808.查询终端参数);
            peer.write(query);
            T0104 response = assertInstanceOf(T0104.class,
                    peer.readUntil(message -> message instanceof T0104));
            assertEquals(set.getSerialNo() + 1, response.getResponseSerialNo());
            assertEquals(1L, response.getParameters().get(0x0001));
            assertEquals(95L, response.getParameters().get(0x0055));

            T8105 reset = platform(new T8105().setCommand(4).setParameter(""), authenticated, 602);
            peer.write(reset);
            assertGeneralResponse(peer.readUntil(message -> message instanceof T0001),
                    reset, T0001.Success);
        };
        Consumer<FakePeer> authenticateAfterReset = peer -> {
            authenticate(peer);
            peer.readUntil(message -> message.getMessageId() == JT808.终端心跳);
        };

        try (FakeSignalServer server = new FakeSignalServer(manageAndReset, authenticateAfterReset);
                SignalClient client = new SignalClient(
                        config(Jt808Version.V2013, server.port()), new RecordingCommandHandler(),
                        listener, LocationSource.NONE, FAST_TIMING, () -> 0.5d)) {
            client.connect();
            server.await(Duration.ofSeconds(8));
            assertTrue(listener.online.await(2, TimeUnit.SECONDS));
            assertEquals(1L, client.terminalParameters().get(0x0001));
            assertTrue(listener.states.contains(SignalState.RECONNECT_WAIT));
        }
    }

    @Test
    void answersImmediateLocationAndDeliversUrgentText() throws Exception {
        RecordingListener listener = new RecordingListener();
        LocationSource source = now -> new LocationFix(
                31.230416D, 121.473701D, 12, 60, 87, 100,
                LocalDateTime.of(2026, 8, 21, 8, 0));
        CompletableFuture<T0201_0500> queried = new CompletableFuture<>();
        try (FakeSignalServer server = new FakeSignalServer(peer -> {
            authenticate(peer);
            T0200 location = assertInstanceOf(T0200.class,
                    peer.readUntil(message -> message instanceof T0200));
            JTMessage query = platform(new JTMessage(), location, 710);
            query.setMessageId(JT808.位置信息查询);
            peer.write(query);
            queried.complete(assertInstanceOf(T0201_0500.class,
                    peer.readUntil(message -> message instanceof T0201_0500)));

            T8300 text = platform(new T8300().setSign(1).setType(1).setContent("立即停车"),
                    location, 711);
            peer.write(text);
            assertGeneralResponse(peer.readUntil(message -> message instanceof T0001),
                    text, T0001.Success);
        }); SignalClient client = new SignalClient(
                config(Jt808Version.V2013, server.port()), new RecordingCommandHandler(), listener,
                source, FAST_TIMING, () -> 0.5d)) {
            client.connect();
            T0201_0500 response = queried.get(5, TimeUnit.SECONDS);
            assertEquals(710, response.getResponseSerialNo());
            assertEquals(31_230_416, response.getLatitude());
            server.await(Duration.ofSeconds(5));
            assertEquals("立即停车", listener.terminalText);
            assertTrue(listener.terminalTextUrgent);
        }
    }

    @Test
    void reportsConfiguredUpgradeSuccessAndFailureResults() throws Exception {
        assertUpgradeResult(false, 0);
        assertUpgradeResult(true, 1);
    }

    private static void assertUpgradeResult(boolean fail, int expectedResult) throws Exception {
        CompletableFuture<T0108> result = new CompletableFuture<>();
        try (FakeSignalServer server = new FakeSignalServer(peer -> {
            authenticate(peer);
            JTMessage context = peer.readUntil(message -> message.getMessageId() == JT808.终端心跳);
            T8108 upgrade = platform(new T8108()
                    .setType(T8108.Terminal)
                    .setMakerId("JT")
                    .setVersion("2.0")
                    .setPacket(new byte[] {1, 2, 3, 4}), context, 700);
            peer.write(upgrade);
            result.complete(assertInstanceOf(T0108.class,
                    peer.readUntil(message -> message instanceof T0108)));
        })) {
            SimulatorConfig base = config(Jt808Version.V2013, server.port());
            SimulatorConfig configured = base.withTerminalManagement(
                    new TerminalManagementConfig(base.terminalManagement().parameters(), 0, fail));
            try (SignalClient client = new SignalClient(
                    configured, new RecordingCommandHandler(), new RecordingListener(),
                    LocationSource.NONE, FAST_TIMING, () -> 0.5d)) {
                client.connect();
                assertEquals(expectedResult, result.get(5, TimeUnit.SECONDS).getResult());
                server.await(Duration.ofSeconds(5));
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
                    Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(2));
            try (SignalClient client = new SignalClient(
                    config(Jt808Version.V2013, server.port()),
                    new RecordingCommandHandler(), listener, LocationSource.NONE, timing, () -> 0.5d)) {
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
                        LocationSource.NONE, FAST_TIMING, () -> 0.5d)) {
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
                LocationSource.NONE, FAST_TIMING, () -> 0.5d)) {
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
                        LocationSource.NONE, FAST_TIMING, () -> 0.5d)) {
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

    /**
     * 位置汇报的字段单位与标志位。
     *
     * <p>其中**定位标志（bit1）是整个行程功能的生死线**：平台对未定位的位置直接丢弃，
     * 车会「在跑」但平台上什么都不会出现，而且不报任何错。
     */
    @ParameterizedTest
    @EnumSource(Jt808Version.class)
    void reportsLocationWithTheUnitsAndFlagsThePlatformRequires(Jt808Version version)
            throws Exception {
        RecordingListener listener = new RecordingListener();
        CompletableFuture<T0200> reported = new CompletableFuture<>();
        LocationSource source = now -> new LocationFix(
                31.230416D, 121.473701D, 12, 60.0D, 87, 3_456.0D,
                LocalDateTime.of(2026, 8, 17, 10, 30, 0));

        try (FakeSignalServer server = new FakeSignalServer(peer -> {
            authenticate(peer);
            reported.complete(assertInstanceOf(T0200.class,
                    peer.readUntil(message -> message instanceof T0200)));
        }); SignalClient client = new SignalClient(
                config(version, server.port()), new RecordingCommandHandler(), listener,
                source, FAST_TIMING, () -> 0.5d)) {
            client.connect();

            T0200 location = reported.get(5, TimeUnit.SECONDS);

            assertTrue((location.getStatusBit() & 0b10) != 0, "定位标志未置位，平台会丢弃这条位置");
            // 加密标志必须为 0：上报的是原始坐标系。
            assertEquals(0, location.getStatusBit() & (1 << 5));
            assertEquals(31_230_416, location.getLatitude());
            assertEquals(121_473_701, location.getLongitude());
            assertEquals(12, location.getAltitude());
            assertEquals(600, location.getSpeed(), "速度单位是 1/10 km/h");
            assertEquals(87, location.getDirection());
            assertEquals(LocalDateTime.of(2026, 8, 17, 10, 30, 0), location.getDeviceTime());
            // 里程按 1/10 km 编码，且必须是 Long——放 Integer 会在编码时强转失败。
            Object mileage = location.getAttributes().get(AttributeKey.Mileage);
            assertInstanceOf(Long.class, mileage);
            assertEquals(35L, mileage);
        }
    }

    /** 南纬西经由状态标志位表达，经纬度字段本身是无符号的。 */
    @Test
    void encodesSouthernAndWesternCoordinatesWithFlagsRatherThanNegativeNumbers() throws Exception {
        CompletableFuture<T0200> reported = new CompletableFuture<>();
        LocationSource source = now -> new LocationFix(
                -33.8688D, -151.2093D, 0, 10.0D, 180, 0.0D,
                LocalDateTime.of(2026, 8, 17, 10, 30, 0));

        try (FakeSignalServer server = new FakeSignalServer(peer -> {
            authenticate(peer);
            reported.complete(assertInstanceOf(T0200.class,
                    peer.readUntil(message -> message instanceof T0200)));
        }); SignalClient client = new SignalClient(
                config(Jt808Version.V2013, server.port()), new RecordingCommandHandler(),
                new RecordingListener(), source, FAST_TIMING, () -> 0.5d)) {
            client.connect();

            T0200 location = reported.get(5, TimeUnit.SECONDS);

            assertTrue(location.getLatitude() > 0, "纬度字段是无符号的");
            assertTrue(location.getLongitude() > 0, "经度字段是无符号的");
            assertEquals(33_868_800, location.getLatitude());
            assertEquals(151_209_300, location.getLongitude());
            assertTrue((location.getStatusBit() & (1 << 2)) != 0, "南纬标志位未置位");
            assertTrue((location.getStatusBit() & (1 << 3)) != 0, "西经标志位未置位");
        }
    }

    @Test
    void sendsNothingWhileTheLocationSourceHasNothingToReport() throws Exception {
        // 行程未开启时来源一直返回 null，终端表现得和从前完全一样。
        CompletableFuture<JTMessage> unexpected = new CompletableFuture<>();
        CountDownLatch heartbeats = new CountDownLatch(3);

        try (FakeSignalServer server = new FakeSignalServer(peer -> {
            authenticate(peer);
            while (heartbeats.getCount() > 0) {
                JTMessage message = peer.read();
                if (message instanceof T0200) {
                    unexpected.complete(message);
                    return;
                }
                if (message.getMessageId() == JT808.终端心跳) {
                    heartbeats.countDown();
                }
            }
        }); SignalClient client = new SignalClient(
                config(Jt808Version.V2013, server.port()), new RecordingCommandHandler(),
                new RecordingListener(), LocationSource.NONE, FAST_TIMING, () -> 0.5d)) {
            client.connect();

            assertTrue(heartbeats.await(5, TimeUnit.SECONDS), "心跳应当照常发送");
            assertFalse(unexpected.isDone(), "来源返回空时不该发出位置汇报");
        }
    }

    /**
     * 来源抛出运行时异常时，上报**必须继续**。
     *
     * <p>周期调度器的任务一旦抛出异常就会静默取消后续所有调度——不吞掉的话，一次偶发异常就会让
     * 位置上报永久停止，而日志里什么都看不到。
     */
    @Test
    void keepsReportingAfterTheLocationSourceThrows() throws Exception {
        AtomicInteger samples = new AtomicInteger();
        LocationSource flaky = now -> {
            if (samples.incrementAndGet() <= 2) {
                throw new IllegalStateException("模拟来源故障");
            }
            return new LocationFix(31.0D, 121.0D, 0, 60.0D, 90, 100.0D,
                    LocalDateTime.of(2026, 8, 17, 10, 30, 0));
        };
        CompletableFuture<T0200> recovered = new CompletableFuture<>();

        try (FakeSignalServer server = new FakeSignalServer(peer -> {
            authenticate(peer);
            recovered.complete(assertInstanceOf(T0200.class,
                    peer.readUntil(message -> message instanceof T0200)));
        }); SignalClient client = new SignalClient(
                config(Jt808Version.V2013, server.port()), new RecordingCommandHandler(),
                new RecordingListener(), flaky, FAST_TIMING, () -> 0.5d)) {
            client.connect();

            assertEquals(31_000_000, recovered.get(5, TimeUnit.SECONDS).getLatitude());
            assertTrue(samples.get() > 2);
        }
    }

    /** 行程状态挂在连接之外，因此断线重连后位置上报自动恢复。 */
    @Test
    void resumesLocationReportingAfterReconnecting() throws Exception {
        CountDownLatch locations = new CountDownLatch(2);
        LocationSource source = now -> new LocationFix(31.0D, 121.0D, 0, 60.0D, 90, 100.0D,
                LocalDateTime.of(2026, 8, 17, 10, 30, 0));
        Consumer<FakePeer> reportThenClose = peer -> {
            authenticate(peer);
            peer.readUntil(message -> message instanceof T0200);
            locations.countDown();
            peer.close();
        };

        try (FakeSignalServer server = new FakeSignalServer(reportThenClose, reportThenClose);
                SignalClient client = new SignalClient(
                        config(Jt808Version.V2013, server.port()), new RecordingCommandHandler(),
                        new RecordingListener(2), source, FAST_TIMING, () -> 0.5d)) {
            client.connect();

            assertTrue(locations.await(10, TimeUnit.SECONDS),
                    "重连后应当继续上报位置，而不是就此停住");
        }
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
                source.previewFps(), source.maxPayloadBytes(), source.trip());
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
        private volatile String terminalText;
        private volatile boolean terminalTextUrgent;

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

        @Override
        public void onTerminalText(String content, boolean urgent) {
            terminalText = content;
            terminalTextUrgent = urgent;
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
