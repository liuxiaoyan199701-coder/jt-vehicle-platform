package io.github.jtplatform.boot;

import static io.github.jtplatform.boot.PlatformEndToEndScenario.CHANNEL;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.MOBILE_NO;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.TERMINAL_ID;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.connect;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.prepare;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.readDelimitedFrame;
import static io.github.jtplatform.boot.PlatformEndToEndScenario.videoPacket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.codec.JTMessageEncoder;
import org.yzh.protocol.t1078.T9101;
import org.yzh.protocol.t808.T0001;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0102;
import org.yzh.protocol.t808.T8100;
import org.yzh.protocol.t808.T8801;
import tools.jackson.databind.ObjectMapper;

/**
 * 链路诊断事件的端到端验证：真实 Netty 接入、真实 Spring 装配、真实投递器。
 *
 * <p>单测能证明每一跳各自正确，证明不了它们接在一起还对——指令结局挂在
 * {@code MessageManager} 的构造注入上，无流到达挂在装配层的可选 Bean 上，
 * 两者都是「装配错了单测照样全绿」的地方。
 *
 * <p>用实例 2 的端口组（信令 7102、媒体 7820-7826），避免与同 JVM 里
 * 实例 1 的上下文抢端口。
 */
@SpringBootTest(
        classes = JtPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "jt.instance.number=2",
                "jt.signal.tcp-port=7102",
                "jt.signal.udp-port=7103",
                "jt.media.pending-timeout=2s",
                "jt.media.reachable-address.source=static",
                "jt.media.reachable-address.value=127.0.0.1"
        })
@ActiveProfiles("standalone")
@Import(LinkDiagnosticsEndToEndTest.RecordingPublisherConfiguration.class)
class LinkDiagnosticsEndToEndTest {
    private static final int SIGNAL_PORT = 7102;

    @LocalServerPort
    private int apiPort;

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

    /** 终端对 0x8801 回 T0001「不支持」→ 落到 COMMAND_RESULT/REJECTED，而不是干等到超时。 */
    @Test
    void terminalRejectionOfAPhotoCommandIsPublishedAsACommandResult() throws Exception {
        try (Socket signal = connect(SIGNAL_PORT)) {
            authenticate(signal);
            postAsync("device/8801", Map.of("clientId", MOBILE_NO, "command", 0, "channelNo", CHANNEL));

            T8801 photoCommand = read(signal, T8801.class);
            // 终端通用应答是 0x0001；prepare() 按注解取的是 0x8001（平台通用应答），必须改回来。
            write(signal, prepare(new T0001()
                    .setResponseSerialNo(photoCommand.getSerialNo())
                    .setResponseMessageId(0x8801)
                    .setResultCode(T0001.NotSupport), 3)
                    .setMessageId(JT808.终端通用应答));

            MessageEnvelope event = awaitEvent("COMMAND_RESULT", "0x8801");
            assertThat(detail(event)).containsEntry("outcome", "REJECTED")
                    .containsEntry("resultCode", 3);
            assertEquals(MOBILE_NO, event.deviceId());
        }
    }

    /** 开流后终端不推流 → 等待窗口结束时落到 STREAM_NOT_ARRIVED，带上等待中的媒体节点。 */
    @Test
    void openingAStreamThatNeverArrivesIsPublishedAsStreamNotArrived() throws Exception {
        try (Socket signal = connect(SIGNAL_PORT)) {
            authenticate(signal);
            openStream("main");
            read(signal, T9101.class);

            MessageEnvelope event = awaitEvent("STREAM_NOT_ARRIVED", null);
            assertThat(detail(event)).containsEntry("channel", CHANNEL)
                    .containsEntry("streamKind", "main")
                    .containsEntry("mediaInstanceId", "media-1");
            assertThat((Number) detail(event).get("waitedMs")).isNotNull();
        }
    }

    /** 码流正常到达 → 不得产生无流到达事件，否则诊断会把好车报成故障。 */
    @Test
    void aStreamThatArrivesNeverProducesANotArrivedEvent() throws Exception {
        try (Socket signal = connect(SIGNAL_PORT)) {
            authenticate(signal);
            openStream("sub");
            T9101 command = read(signal, T9101.class);

            try (Socket video = connect(command.getTcpPort())) {
                video.getOutputStream().write(videoPacket());
                video.getOutputStream().flush();
                // 等过整个 pending 窗口：误报只会在窗口到期的那一刻发生。
                TimeUnit.SECONDS.sleep(4);
            }

            assertThat(find("STREAM_NOT_ARRIVED", null)).isEmpty();
        }
    }

    private void authenticate(Socket signal) throws Exception {
        write(signal, prepare(new T0100()
                .setProvinceId(31).setCityId(100).setMakerId("JT").setDeviceModel("LINK")
                .setDeviceId(TERMINAL_ID).setPlateColor(1).setPlateNo("TEST001"), 1));
        T8100 registration = read(signal, T8100.class);
        assertEquals(T8100.Success, registration.getResultCode());

        write(signal, prepare(new T0102().setToken(registration.getToken()), 2));
        assertEquals(T0001.Success, read(signal, T0001.class).getResultCode());
    }

    private void openStream(String streamKind) throws Exception {
        HttpResponse<String> response = post("stream/open", Map.of(
                "deviceId", MOBILE_NO, "channel", CHANNEL, "streamKind", streamKind));
        assertEquals(200, response.statusCode(), response.body());
    }

    private HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        return httpClient.send(request(path, body), HttpResponse.BodyHandlers.ofString());
    }

    /** 指令类接口要等终端应答才返回，必须异步发出，否则本线程就没法去扮演终端。 */
    private void postAsync(String path, Map<String, Object> body) {
        httpClient.sendAsync(request(path, body), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String path, Map<String, Object> body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + apiPort + '/').resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    private MessageEnvelope awaitEvent(String kind, String commandMsgId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<MessageEnvelope> found = find(kind, commandMsgId).stream().findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("没有等到 " + kind + " 事件，已发布："
                + RecordingPublisher.EVENTS.stream().map(event -> event.payload().get("kind")).toList());
    }

    private static List<MessageEnvelope> find(String kind, String commandMsgId) {
        List<MessageEnvelope> matched = new ArrayList<>();
        for (MessageEnvelope event : RecordingPublisher.EVENTS) {
            if (!kind.equals(event.payload().get("kind"))) {
                continue;
            }
            if (commandMsgId == null || commandMsgId.equals(detail(event).get("commandMsgId"))) {
                matched.add(event);
            }
        }
        return matched;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(MessageEnvelope event) {
        return event.payload().get("detail") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
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
