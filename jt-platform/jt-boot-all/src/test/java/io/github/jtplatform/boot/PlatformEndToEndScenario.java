package io.github.jtplatform.boot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.media.protocol.FragmentFlag;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.github.jtplatform.signal.protocol.SignalMultiPacketDecoder;
import io.github.yezhihao.protostar.SchemaManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.codec.JTMessageEncoder;
import org.yzh.protocol.t1078.T9101;
import org.yzh.protocol.t808.T0001;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0102;
import org.yzh.protocol.t808.T8100;
import tools.jackson.databind.ObjectMapper;

final class PlatformEndToEndScenario {
    private static final String TERMINAL_ID = "1380000";
    private static final String MOBILE_NO = "138000000000";
    private static final int CHANNEL = 1;

    private final Endpoints endpoints;
    private final SchemaManager schemas = new SchemaManager("org.yzh.protocol");
    private final JTMessageEncoder signalEncoder = new JTMessageEncoder(schemas);
    private final SignalMultiPacketDecoder signalDecoder = new SignalMultiPacketDecoder(schemas, null);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private PlatformEndToEndScenario(Endpoints endpoints) {
        this.endpoints = endpoints;
    }

    static void run(Endpoints endpoints) throws Exception {
        new PlatformEndToEndScenario(endpoints).run();
    }

    private void run() throws Exception {
        try (Socket signal = connect(endpoints.signalTcpPort())) {
            authenticateTerminal(signal);

            OpenedStream live = openStream("main");
            T9101 liveCommand = readSignal(signal, T9101.class);
            assertEquals("127.0.0.1", liveCommand.getIp());
            assertEquals(endpoints.mediaMainPort(), liveCommand.getTcpPort());
            assertEquals(CHANNEL, liveCommand.getChannelNo());
            assertEquals(endpoints.mediaWebSocketPort(), URI.create(live.wsUrl()).getPort());

            WebSocketProbe liveProbe = new WebSocketProbe();
            WebSocket liveWebSocket = connectWebSocket(live.wsUrl(), liveProbe);
            try (Socket video = connect(endpoints.mediaMainPort())) {
                assertTrue(liveProbe.waking().get(3, TimeUnit.SECONDS).contains("\"state\":\"waking\""));
                video.getOutputStream().write(videoPacket());
                video.getOutputStream().flush();

                assertTrue(liveProbe.live().get(3, TimeUnit.SECONDS).contains("\"state\":\"live\""));
                byte[] keyFrame = liveProbe.keyFrame().get(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] {'J', 'T', '7', '8'}, java.util.Arrays.copyOf(keyFrame, 4));
                assertEquals(0, keyFrame[4] & 0xff);
                assertEquals(CHANNEL, keyFrame[5] & 0xff);
            } finally {
                liveWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "live smoke complete").join();
            }

            OpenedStream talkback = openStream("talkback");
            T9101 talkbackCommand = readSignal(signal, T9101.class);
            assertEquals(endpoints.mediaTalkbackPort(), talkbackCommand.getTcpPort());
            assertEquals(2, talkbackCommand.getMediaType());

            WebSocketProbe talkbackProbe = new WebSocketProbe();
            WebSocket talkbackWebSocket = connectWebSocket(talkback.wsUrl(), talkbackProbe);
            try (Socket deviceAudio = connect(endpoints.mediaTalkbackPort())) {
                assertTrue(talkbackProbe.waking().get(3, TimeUnit.SECONDS)
                        .contains("\"state\":\"waking\""));
                deviceAudio.setSoTimeout(3_000);
                deviceAudio.getOutputStream().write(audioPacket(new byte[] {(byte) 0xd5}));
                deviceAudio.getOutputStream().flush();
                assertTrue(talkbackProbe.live().get(3, TimeUnit.SECONDS)
                        .contains("\"state\":\"live\""));

                byte[] upstream = new byte[160];
                java.util.Arrays.fill(upstream, (byte) 0xd4);
                talkbackWebSocket.sendBinary(ByteBuffer.wrap(upstream), true).join();

                DataInputStream input = new DataInputStream(deviceAudio.getInputStream());
                byte[] header = input.readNBytes(26);
                assertEquals(26, header.length);
                assertArrayEquals(new byte[] {0x30, 0x31, 0x63, 0x64},
                        java.util.Arrays.copyOf(header, 4));
                int payloadLength = ((header[24] & 0xff) << 8) | (header[25] & 0xff);
                assertEquals(upstream.length, payloadLength);
                assertArrayEquals(upstream, input.readNBytes(payloadLength));
            } finally {
                talkbackWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "talkback smoke complete").join();
            }
        }
    }

    private void authenticateTerminal(Socket signal) throws Exception {
        T0100 registration = prepare(new T0100()
                .setProvinceId(31)
                .setCityId(100)
                .setMakerId("JT")
                .setDeviceModel("SMOKE")
                .setDeviceId(TERMINAL_ID)
                .setPlateColor(1)
                .setPlateNo("TEST001"), 1);
        writeSignal(signal, registration);
        T8100 registrationResponse = readSignal(signal, T8100.class);
        assertEquals(T8100.Success, registrationResponse.getResultCode());
        assertTrue(registrationResponse.getToken() != null && !registrationResponse.getToken().isBlank());

        T0102 authentication = prepare(new T0102().setToken(registrationResponse.getToken()), 2);
        writeSignal(signal, authentication);
        T0001 authenticationResponse = readSignal(signal, T0001.class);
        assertEquals(T0001.Success, authenticationResponse.getResultCode());
    }

    private OpenedStream openStream(String streamKind) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "deviceId", MOBILE_NO,
                "channel", CHANNEL,
                "streamKind", streamKind));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoints.apiBaseUri().resolve("stream/open"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        assertEquals("waking", result.get("state"));
        return new OpenedStream(result.get("wsUrl").toString(), result.get("token").toString());
    }

    private WebSocket connectWebSocket(String wsUrl, WebSocketProbe probe) throws Exception {
        return httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create(wsUrl), probe)
                .get(3, TimeUnit.SECONDS);
    }

    private void writeSignal(Socket socket, JTMessage message) throws IOException {
        ByteBuf encoded = signalEncoder.encode(message);
        try {
            socket.getOutputStream().write(ByteBufUtil.getBytes(encoded));
            socket.getOutputStream().flush();
        } finally {
            ReferenceCountUtil.safeRelease(encoded);
        }
    }

    private <T extends JTMessage> T readSignal(Socket socket, Class<T> type) throws Exception {
        socket.setSoTimeout(5_000);
        ByteBuf frame = Unpooled.wrappedBuffer(readDelimitedFrame(socket));
        try {
            return assertInstanceOf(type, signalDecoder.decode(frame));
        } finally {
            ReferenceCountUtil.safeRelease(frame);
        }
    }

    private static byte[] readDelimitedFrame(Socket socket) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        boolean started = false;
        while (true) {
            int next = socket.getInputStream().read();
            if (next < 0) {
                throw new IOException("Signal connection closed before a complete frame arrived");
            }
            if (!started) {
                if (next == 0x7e) {
                    started = true;
                    frame.write(next);
                }
                continue;
            }
            frame.write(next);
            if (next == 0x7e) {
                return frame.toByteArray();
            }
        }
    }

    private static <T extends JTMessage> T prepare(T message, int serialNo) {
        message.setMessageId(message.reflectMessageId());
        message.setClientId(MOBILE_NO);
        message.setSerialNo(serialNo);
        message.setEncryption(0);
        message.setReserved(false);
        return message;
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
        return socket;
    }

    private static byte[] videoPacket() {
        byte[] payload = {
                0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f,
                0, 0, 0, 1, 0x68, (byte) 0xce, 0x06, (byte) 0xe2,
                0, 0, 0, 1, 0x65, (byte) 0x88, (byte) 0x84
        };
        return mediaPacket(Jt1078Constants.VIDEO_I_FRAME, Jt1078Constants.PT_H264, payload);
    }

    private static byte[] audioPacket(byte[] payload) {
        return mediaPacket(Jt1078Constants.AUDIO_FRAME, Jt1078Constants.PT_G711A, payload);
    }

    private static byte[] mediaPacket(int dataType, int payloadType, byte[] payload) {
        boolean video = dataType <= Jt1078Constants.VIDEO_B_FRAME;
        ByteBuffer packet = ByteBuffer.allocate((video ? 30 : 26) + payload.length)
                .order(ByteOrder.BIG_ENDIAN);
        packet.put(new byte[] {0x30, 0x31, 0x63, 0x64});
        packet.put((byte) 0x81);
        packet.put((byte) (0x80 | payloadType));
        packet.putShort((short) 1);
        packet.put(encodeBcd(MOBILE_NO));
        packet.put((byte) CHANNEL);
        packet.put((byte) ((dataType << 4) | FragmentFlag.ATOMIC.wireValue()));
        packet.putLong(System.currentTimeMillis() * 1_000L);
        if (video) {
            packet.putShort((short) 40);
            packet.putShort((short) 40);
        }
        packet.putShort((short) payload.length);
        packet.put(payload);
        return packet.array();
    }

    private static byte[] encodeBcd(String value) {
        String digits = "0".repeat(12 - value.length()) + value;
        byte[] encoded = new byte[6];
        for (int index = 0; index < encoded.length; index++) {
            int high = digits.charAt(index * 2) - '0';
            int low = digits.charAt(index * 2 + 1) - '0';
            encoded[index] = (byte) ((high << 4) | low);
        }
        return encoded;
    }

    record Endpoints(
            URI apiBaseUri,
            int signalTcpPort,
            int mediaMainPort,
            int mediaTalkbackPort,
            int mediaWebSocketPort) {
    }

    private record OpenedStream(String wsUrl, String token) {
    }

    private static final class WebSocketProbe implements WebSocket.Listener {
        private final CompletableFuture<String> waking = new CompletableFuture<>();
        private final CompletableFuture<String> live = new CompletableFuture<>();
        private final CompletableFuture<byte[]> keyFrame = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();
        private final List<ByteBuffer> binaryParts = new ArrayList<>();

        CompletableFuture<String> waking() {
            return waking;
        }

        CompletableFuture<String> live() {
            return live;
        }

        CompletableFuture<byte[]> keyFrame() {
            return keyFrame;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                if (message.contains("\"state\":\"waking\"")) {
                    waking.complete(message);
                }
                if (message.contains("\"state\":\"live\"")) {
                    live.complete(message);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] part = new byte[data.remaining()];
            data.get(part);
            binaryParts.add(ByteBuffer.wrap(part));
            if (last) {
                int length = binaryParts.stream().mapToInt(ByteBuffer::remaining).sum();
                ByteBuffer complete = ByteBuffer.allocate(length);
                binaryParts.forEach(complete::put);
                byte[] frame = complete.array();
                binaryParts.clear();
                if (frame.length >= 8 && (frame[4] & 0xff) == 0) {
                    keyFrame.complete(frame);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            waking.completeExceptionally(error);
            live.completeExceptionally(error);
            keyFrame.completeExceptionally(error);
        }
    }
}
