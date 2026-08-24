package io.github.jtplatform.boot;

import static io.github.jtplatform.boot.PlatformEndToEndScenario.CHANNEL;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.MOBILE_NO;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.TERMINAL_ID;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.connect;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.prepare;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.readDelimitedFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import io.github.jtplatform.signal.messagelog.DeliveringMessageLogEmitter;
import io.github.jtplatform.signal.messagelog.MessageLogEmitter;
import io.github.jtplatform.signal.protocol.SignalMultiPacketDecoder;
import io.github.yezhihao.protostar.SchemaManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.codec.JTMessageEncoder;
import org.yzh.protocol.t808.T0001;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0102;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T8100;
import org.yzh.protocol.t808.T8801;
import tools.jackson.databind.ObjectMapper;

/**
 * 报文日志采集的端到端验证：真实 Netty 接入、真实 Spring 装配、真实投递器。
 *
 * <p>采集点挂在编解码适配器的两个钩子和一次 {@code decode} 覆写上——这三处都是「单测全绿、
 * 装配错了照样一条日志都收不到」的位置：适配器 Bean 少注一个参数、开关默认值写反、
 * 钩子被上游改了签名，任何一样都只有在真链路上才看得见。
 *
 * <p><b>边界</b>：本测试证明的是网关**发出**了正确的 device_log 信封。信封变成日志库里的行
 * 是控制台侧的事，由 {@code DeviceLogIngestionServiceTest} 与
 * {@code ConnectionEventDeviceLogMirrorTest} 覆盖——两边合起来才是完整链路。
 *
 * <p>用实例 3 的端口组（信令 7104/7105），避免与同 JVM 里实例 1、2 的上下文抢端口。
 */
@SpringBootTest(
        classes = JtPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "jt.instance.number=3",
                "jt.signal.tcp-port=7104",
                "jt.signal.udp-port=7105",
                "jt.media.reachable-address.source=static",
                "jt.media.reachable-address.value=127.0.0.1"
        })
@ActiveProfiles("standalone")
@Import(DeviceLogEndToEndTest.RecordingPublisherConfiguration.class)
class DeviceLogEndToEndTest {
    private static final int SIGNAL_PORT = 7104;

    @LocalServerPort
    private int apiPort;

    @Autowired
    private MessageLogEmitter emitter;

    private final SchemaManager schemas = new SchemaManager("org.yzh.protocol");
    private final JTMessageEncoder encoder = new JTMessageEncoder(schemas);
    private final SignalMultiPacketDecoder decoder = new SignalMultiPacketDecoder(schemas, null);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach
    void clearPublishedEvents() {
        RecordingPublisher.EVENTS.clear();
    }

    /** 装配错了就一条日志都收不到，而且什么都不会报——所以先把这一处钉住。 */
    @Test
    void theCollectorIsActuallyWiredInsteadOfDegradingToTheNoOp() {
        assertThat(emitter).isInstanceOf(DeliveringMessageLogEmitter.class);
    }

    /** 上报 0x0200 → 一条 UP 日志，原始 hex 能反解回同一条报文，解析结果带得上正文。 */
    @Test
    void aLocationReportIsLoggedWithBothTheRawFrameAndTheParsedBody() throws Exception {
        try (Socket signal = connect(SIGNAL_PORT)) {
            authenticate(signal);

            write(signal, prepare(new T0200()
                    .setLatitude(31_230_000).setLongitude(121_470_000)
                    .setSpeed(60).setAltitude(12).setDirection(90)
                    .setDeviceTime(LocalDateTime.parse("2026-08-24T09:02:03")), 3));

            MessageEnvelope logged = awaitLog(log -> "UP".equals(direction(log))
                    && "0x0200".equals(log.payload().get("msgIdHex")));
            assertEquals(MOBILE_NO, logged.deviceId());
            assertEquals("0", logged.payload().get("decodeError"));
            assertEquals("3", logged.payload().get("serialNo"));

            // 反解一遍原始 hex：留下来的字节必须真的是那条报文，而不是一串看着像 hex 的东西。
            String rawHex = (String) logged.payload().get("rawHex");
            assertThat(rawHex).isNotEmpty();
            ByteBuf recovered = Unpooled.wrappedBuffer(HexFormat.of().parseHex(rawHex));
            try {
                T0200 decoded = assertInstanceOf(T0200.class, decoder.decode(recovered));
                assertEquals(3, decoded.getSerialNo());
                assertEquals(31_230_000, decoded.getLatitude());
            } finally {
                ReferenceCountUtil.safeRelease(recovered);
            }

            assertThat((String) logged.payload().get("parsedJson"))
                    .contains("speedKph").contains("latitude");
        }
    }

    /** 下发 0x8801 → 一条 DOWN 日志，记录的是完整成帧字节。 */
    @Test
    void aPhotoCommandIsLoggedOnTheWayOut() throws Exception {
        try (Socket signal = connect(SIGNAL_PORT)) {
            authenticate(signal);
            postAsync("device/8801", Map.of("clientId", MOBILE_NO, "command", 0, "channelNo", CHANNEL));

            T8801 photoCommand = read(signal, T8801.class);

            MessageEnvelope logged = awaitLog(log -> "DOWN".equals(direction(log))
                    && "0x8801".equals(log.payload().get("msgIdHex")));
            assertEquals(MOBILE_NO, logged.deviceId());
            assertEquals(String.valueOf(photoCommand.getSerialNo()), logged.payload().get("serialNo"));
            assertThat((String) logged.payload().get("rawHex")).startsWith("7e").endsWith("7e");
            assertThat((String) logged.payload().get("parsedJson")).contains("channelId");
        }
    }

    /**
     * 设备上线 → 一条 CONNECTION 事件。
     *
     * <p>控制台接手这个信封时会往日志库补一条 {@code direction=CONNECTION} 的记录，
     * 设备时间线因此单表可查；网关不为此重复发事件，所以这里能断言的就是「事件发出来了」。
     */
    @Test
    void loggingInPublishesTheConnectionEventTheConsoleMirrorsIntoTheTimeline() throws Exception {
        try (Socket signal = connect(SIGNAL_PORT)) {
            authenticate(signal);

            MessageEnvelope connected = awaitEnvelope(envelope ->
                    "connection".equals(envelope.type().wireValue())
                            && MOBILE_NO.equals(envelope.deviceId()));

            // 控制台的镜像只用这两个字段拼 summary 与 log_time，缺一个时间线就少一格或错位。
            assertThat((String) connected.payload().get("kind")).isNotBlank();
            assertThat((String) connected.payload().get("eventTime")).isNotBlank();
        }
    }

    /**
     * 畸形帧 → 仍然留下原始字节，并带解码失败标记。
     *
     * <p>这条帧走不到 {@code decodeLog}——异常在解码里就抛出来了。而它恰恰是最需要原始字节的
     * 一类：只有那串 hex 能说明终端到底发了什么。
     */
    @Test
    void aFrameThatFailsToDecodeStillLeavesItsRawBytesBehind() throws Exception {
        try (Socket signal = connect(SIGNAL_PORT)) {
            // 定界符齐全但正文长度不足以凑出消息头，解码必然抛异常。
            signal.getOutputStream().write(HexFormat.of().parseHex("7e00017e"));
            signal.getOutputStream().flush();

            MessageEnvelope logged = awaitLog(log -> "1".equals(log.payload().get("decodeError")));
            // 断言「原始字节留下来了」，不断言精确长度：尾定界符算不算进来是 netmc 的实现细节。
            assertThat((String) logged.payload().get("rawHex")).startsWith("0001");
            assertEquals("", logged.payload().get("parsedJson"));
            assertEquals("", logged.payload().get("msgIdHex"));
            // 身份不明的帧照样留证，不能因为认不出是谁就丢掉。
            assertEquals("unknown", logged.deviceId());
        }
    }

    private void authenticate(Socket signal) throws Exception {
        write(signal, prepare(new T0100()
                .setProvinceId(31).setCityId(100).setMakerId("JT").setDeviceModel("LOG")
                .setDeviceId(TERMINAL_ID).setPlateColor(1).setPlateNo("TEST003"), 1));
        T8100 registration = read(signal, T8100.class);
        assertEquals(T8100.Success, registration.getResultCode());

        write(signal, prepare(new T0102().setToken(registration.getToken()), 2));
        assertEquals(T0001.Success, read(signal, T0001.class).getResultCode());
    }

    private static String direction(MessageEnvelope envelope) {
        Object direction = envelope.payload().get("direction");
        return direction == null ? null : direction.toString();
    }

    private MessageEnvelope awaitLog(Predicate<MessageEnvelope> matcher) throws InterruptedException {
        return awaitEnvelope(envelope ->
                "device_log".equals(envelope.type().wireValue()) && matcher.test(envelope));
    }

    private MessageEnvelope awaitEnvelope(Predicate<MessageEnvelope> matcher)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<MessageEnvelope> found = List.copyOf(RecordingPublisher.EVENTS).stream()
                    .filter(matcher).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("没有等到匹配的信封，已发布："
                + RecordingPublisher.EVENTS.stream()
                        .map(event -> event.type().wireValue() + '/' + event.payload().get("direction")
                                + '/' + event.payload().get("msgIdHex"))
                        .toList());
    }

    /** 指令类接口要等终端应答才返回，必须异步发出，否则本线程就没法去扮演终端。 */
    private void postAsync(String path, Map<String, Object> body) {
        httpClient.sendAsync(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + apiPort + '/').resolve(path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void write(Socket socket, JTMessage message) throws IOException {
        ByteBuf encoded = encoder.encode(message);
        try {
            socket.getOutputStream().write(ByteBufUtil.getBytes(encoded));
            socket.getOutputStream().flush();
        } finally {
            ReferenceCountUtil.safeRelease(encoded);
        }
    }

    private <T extends JTMessage> T read(Socket socket, Class<T> type) throws Exception {
        socket.setSoTimeout(10_000);
        ByteBuf frame = Unpooled.wrappedBuffer(readDelimitedFrame(socket));
        try {
            return assertInstanceOf(type, decoder.decode(frame));
        } finally {
            ReferenceCountUtil.safeRelease(frame);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingPublisherConfiguration {
        @Bean
        MessagePublisher recordingMessagePublisher() {
            return new RecordingPublisher();
        }
    }

    /** 顶掉默认的 NoOp 投递器，把平台真正发出去的信封留下来。 */
    static final class RecordingPublisher implements MessagePublisher {
        static final List<MessageEnvelope> EVENTS = new CopyOnWriteArrayList<>();

        @Override
        public PublishResult publish(MessageEnvelope envelope) {
            EVENTS.add(envelope);
            return PublishResult.of("test", PublishDisposition.ACCEPTED);
        }
    }
}
