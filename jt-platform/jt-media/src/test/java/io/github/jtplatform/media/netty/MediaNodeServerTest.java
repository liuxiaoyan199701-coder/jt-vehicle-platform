package io.github.jtplatform.media.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.auth.InMemoryStreamTokenStore;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.config.LegacyMediaPortValidator;
import io.github.jtplatform.media.config.MediaRuntimeProperties;
import io.github.jtplatform.media.config.MediaServerProperties;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.FrameAssembler;
import io.github.jtplatform.media.ingest.FragmentReassembler;
import io.github.jtplatform.media.metrics.MediaNodeLoadMonitor;
import io.github.jtplatform.media.pipeline.FirstFrameListener;
import io.github.jtplatform.media.pipeline.MediaPipeline;
import io.github.jtplatform.media.recording.RecordSink;
import io.github.jtplatform.media.recording.RecordingPlaybackService;
import io.github.jtplatform.media.sink.SinkRegistry;
import io.github.jtplatform.media.sink.WebSocketRawSink;
import io.github.jtplatform.media.talkback.TalkbackMode;
import io.github.jtplatform.media.talkback.TalkbackProperties;
import io.github.jtplatform.media.talkback.TalkbackService;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MediaNodeServerTest {
    private final List<TalkbackService> talkbackServices = new ArrayList<>();
    private final List<RecordingPlaybackService> playbackServices = new ArrayList<>();

    @AfterEach
    void closeTalkbackServices() {
        talkbackServices.forEach(TalkbackService::close);
        playbackServices.forEach(RecordingPlaybackService::close);
    }

    @Test
    void websocketListenerEnforcesOneTimeTokenAndAutomaticallySubscribes() throws Exception {
        int instanceNumber = availableInstance();
        Assumptions.assumeTrue(instanceNumber > 0, "No free 78N0-78N5 instance port group");
        StreamKey streamKey = new StreamKey("device-1", 1, StreamKind.MAIN);
        String instanceId = "media-" + instanceNumber;
        InMemoryStreamTokenStore tokens = new InMemoryStreamTokenStore();
        String token = tokens.issue(streamKey, instanceId, Duration.ofMinutes(1));
        MediaNodeServer server = server(new MockEnvironment(), instanceNumber, true, tokens);
        WebSocket first = null;
        WebSocket replay = null;
        try {
            server.start();
            String uri = "ws://127.0.0.1:" + server.ports().websocket()
                    + "/ws?deviceId=device-1&channel=1&streamKind=main&token=" + token;
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            WebSocketProbe firstProbe = new WebSocketProbe();
            first = client.newWebSocketBuilder().buildAsync(URI.create(uri), firstProbe)
                    .get(3, TimeUnit.SECONDS);
            assertTrue(firstProbe.firstText.get(3, TimeUnit.SECONDS).contains("\"state\":\"waking\""));

            WebSocketProbe replayProbe = new WebSocketProbe();
            replay = client.newWebSocketBuilder().buildAsync(URI.create(uri), replayProbe)
                    .get(3, TimeUnit.SECONDS);
            assertTrue(replayProbe.firstText.get(3, TimeUnit.SECONDS).contains("AUTH_TOKEN_REPLAYED"));
            assertEquals(4003, replayProbe.closeCode.get(3, TimeUnit.SECONDS));
        } finally {
            if (replay != null) {
                replay.abort();
            }
            if (first != null) {
                first.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join();
            }
            server.stop();
        }
    }

    @Test
    void instancesOneAndTwoStartTogetherWithoutPortConflict() {
        Assumptions.assumeTrue(instanceAvailable(1) && instanceAvailable(2),
                "Media instance 1 or 2 port group is already occupied");
        MediaNodeServer first = server(new MockEnvironment(), 1);
        MediaNodeServer second = server(new MockEnvironment(), 2);

        try {
            first.start();
            second.start();

            assertTrue(first.isRunning());
            assertTrue(second.isRunning());
            assertEquals(7810, first.ports().management());
            assertEquals(7820, second.ports().management());
            assertEquals(7815, first.ports().websocket());
            assertEquals(7825, second.ports().websocket());
        } finally {
            second.stop();
            first.stop();
        }
    }

    @Test
    void startsAllDerivedInstancePortsAndServesManagementHealth() throws Exception {
        int instanceNumber = availableInstance();
        Assumptions.assumeTrue(instanceNumber > 0, "No free 78N0-78N5 instance port group");
        int base = 7800 + instanceNumber * 10;
        MediaNodeServer server = server(new MockEnvironment(), instanceNumber);
        try {
            server.start();

            assertTrue(server.isRunning());
            assertEquals(base, server.ports().management());
            MediaNodeServer duplicate = server(new MockEnvironment(), instanceNumber);
            try {
                assertThrows(IllegalStateException.class, duplicate::start);
            } finally {
                duplicate.stop();
            }
            for (int port = base + 1; port <= base + 5; port++) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                    assertTrue(socket.isConnected());
                }
            }
            HttpURLConnection connection = (HttpURLConnection) URI.create(
                    "http://127.0.0.1:" + base + "/health").toURL().openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            assertEquals(200, connection.getResponseCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                assertTrue(reader.readLine().contains("\"status\":\"UP\""));
            }
        } finally {
            server.stop();
        }
        assertFalse(server.isRunning());
    }

    @Test
    void exclusiveAndMixModesForwardWebSocketAudioOverTheRealTalkbackSocket() throws Exception {
        exerciseTalkbackRoundTrip(TalkbackMode.EXCLUSIVE);
        exerciseTalkbackRoundTrip(TalkbackMode.MIX);
    }

    @Test
    void legacyPortSettingFailsBeforeAnyListenerStarts() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("jt1078.server.tcp.talkback-port", "6076");
        MediaNodeServer server = server(environment, 8);

        IllegalStateException failure = assertThrows(IllegalStateException.class, server::start);

        assertTrue(failure.getMessage().contains("Deprecated media port setting"));
        assertFalse(server.isRunning());
    }

    private MediaNodeServer server(MockEnvironment environment, int instanceNumber) {
        return server(environment, instanceNumber, false, null);
    }

    private MediaNodeServer server(
            MockEnvironment environment,
            int instanceNumber,
            boolean authenticationEnabled,
            InMemoryStreamTokenStore tokens) {
        return server(environment, instanceNumber, authenticationEnabled, tokens, TalkbackMode.EXCLUSIVE);
    }

    private MediaNodeServer server(
            MockEnvironment environment,
            int instanceNumber,
            boolean authenticationEnabled,
            InMemoryStreamTokenStore tokens,
            TalkbackMode talkbackMode) {
        MediaServerProperties properties = new MediaServerProperties();
        properties.setBindAddress("127.0.0.1");
        properties.setBossThreads(1);
        properties.setWorkerThreads(1);
        properties.setReassemblyTimeout(Duration.ofMillis(100));
        WebSocketRawSink rawSink = new WebSocketRawSink();
        SinkRegistry sinks = new SinkRegistry();
        sinks.register(rawSink);
        MediaPipeline pipeline = new MediaPipeline(
                new FragmentReassembler(Duration.ofMillis(100), 1024 * 1024, Clock.systemUTC()),
                new FrameAssembler(), sinks, FirstFrameListener.noOp());
        TalkbackProperties talkbackProperties = new TalkbackProperties();
        talkbackProperties.setMode(talkbackMode);
        TalkbackService talkbackService = new TalkbackService(talkbackProperties, Clock.systemUTC());
        talkbackServices.add(talkbackService);
        RecordingPlaybackService playbackService = new RecordingPlaybackService(new RecordingProperties());
        playbackServices.add(playbackService);
        return new MediaNodeServer(properties, new LegacyMediaPortValidator(environment),
                pipeline, rawSink, instanceNumber, authenticationEnabled, tokens,
                "media-" + instanceNumber, null,
                 new MediaNodeLoadMonitor(
                         pipeline::activeStreamCount, rawSink::outboundBytes, Clock.systemUTC()),
                 new MediaRuntimeProperties.Capacity(), null, null, new RecordSink(new RecordingProperties()),
                 talkbackService, playbackService, null);
    }

    private void exerciseTalkbackRoundTrip(TalkbackMode mode) throws Exception {
        int instanceNumber = availableInstance();
        Assumptions.assumeTrue(instanceNumber > 0, "No free 78N0-78N5 instance port group");
        MediaNodeServer server = server(
                new MockEnvironment(), instanceNumber, false, null, mode);
        WebSocket webSocket = null;
        try (Socket device = new Socket()) {
            server.start();
            device.connect(new InetSocketAddress("127.0.0.1", server.ports().talkback()), 1000);
            device.setSoTimeout(3000);
            device.getOutputStream().write(talkbackPacket(new byte[] {(byte) 0xd5}));
            device.getOutputStream().flush();

            TalkbackProbe probe = new TalkbackProbe();
            webSocket = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
                    .newWebSocketBuilder()
                    .buildAsync(
                            URI.create("ws://127.0.0.1:" + server.ports().websocket() + "/ws"),
                            probe)
                    .get(3, TimeUnit.SECONDS);
            assertTrue(probe.welcome.get(3, TimeUnit.SECONDS).contains("welcome"));
            webSocket.sendText(
                    "{\"action\":\"subscribe\",\"deviceId\":\"13800138000\"," 
                            + "\"channel\":1,\"streamKind\":\"talkback\"}",
                    true).join();
            assertTrue(probe.live.get(3, TimeUnit.SECONDS).contains("\"state\":\"live\""));

            byte[] upstream = new byte[160];
            Arrays.fill(upstream, (byte) 0xd4);
            webSocket.sendBinary(ByteBuffer.wrap(upstream), true).join();

            DataInputStream input = new DataInputStream(device.getInputStream());
            byte[] header = input.readNBytes(26);
            assertEquals(26, header.length);
            assertArrayEquals(new byte[] {0x30, 0x31, 0x63, 0x64}, Arrays.copyOf(header, 4));
            assertEquals(0x81, header[4] & 0xff);
            assertEquals(0x80 | 6, header[5] & 0xff);
            assertArrayEquals(
                    new byte[] {0x01, 0x38, 0x00, 0x13, (byte) 0x80, 0x00},
                    Arrays.copyOfRange(header, 8, 14));
            assertEquals(1, header[14] & 0xff);
            assertEquals(0x30, header[15] & 0xff);
            int payloadLength = ((header[24] & 0xff) << 8) | (header[25] & 0xff);
            assertEquals(upstream.length, payloadLength);
            assertArrayEquals(upstream, input.readNBytes(payloadLength));
        } finally {
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join();
            }
            server.stop();
        }
    }

    private static byte[] talkbackPacket(byte[] payload) {
        ByteBuffer packet = ByteBuffer.allocate(26 + payload.length).order(ByteOrder.BIG_ENDIAN);
        packet.put(new byte[] {0x30, 0x31, 0x63, 0x64});
        packet.put((byte) 0x81);
        packet.put((byte) (0x80 | 6));
        packet.putShort((short) 1);
        packet.put(new byte[] {0x01, 0x38, 0x00, 0x13, (byte) 0x80, 0x00});
        packet.put((byte) 1);
        packet.put((byte) 0x30);
        packet.putLong(System.currentTimeMillis());
        packet.putShort((short) payload.length);
        packet.put(payload);
        return packet.array();
    }

    private static int availableInstance() {
        for (int instance = 9; instance >= 1; instance--) {
            if (instanceAvailable(instance)) {
                return instance;
            }
        }
        return -1;
    }

    private static boolean instanceAvailable(int instance) {
        int base = 7800 + instance * 10;
        List<ServerSocket> probes = new ArrayList<>();
        try {
            for (int port = base; port <= base + 5; port++) {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress("127.0.0.1", port));
                probes.add(socket);
            }
            return true;
        } catch (Exception unavailable) {
            return false;
        } finally {
            probes.forEach(socket -> {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // Best effort probe cleanup.
                }
            });
        }
    }

    private static final class WebSocketProbe implements WebSocket.Listener {
        private final CompletableFuture<String> firstText = new CompletableFuture<>();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                firstText.complete(text.toString());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            firstText.completeExceptionally(error);
            closeCode.completeExceptionally(error);
        }
    }

    private static final class TalkbackProbe implements WebSocket.Listener {
        private final CompletableFuture<String> welcome = new CompletableFuture<>();
        private final CompletableFuture<String> live = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();

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
                if (message.contains("\"type\":\"welcome\"")) {
                    welcome.complete(message);
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
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            welcome.completeExceptionally(error);
            live.completeExceptionally(error);
        }
    }
}
