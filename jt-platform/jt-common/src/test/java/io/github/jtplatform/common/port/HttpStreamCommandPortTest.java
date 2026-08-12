package io.github.jtplatform.common.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpStreamCommandPortTest {
    @Test
    void playbackUsesTheInternalSignalEndpointAndPreservesTheScheduledTarget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/internal/streams/playback", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"streamId\":\"signal-playback\","
                    + "\"websocketUri\":\"ws://203.0.113.42:49105/ws\","
                    + "\"state\":\"PENDING\","
                    + "\"issuedAt\":\"2026-08-10T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            MediaTarget target = new MediaTarget("media-custom", "203.0.113.42", 49_103, 0, 49_105);
            HttpStreamCommandPort port = new HttpStreamCommandPort(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

            var ticket = port.openPlayback(
                    new StreamKey("device-1", 7, StreamKind.PLAYBACK),
                    target,
                    LocalDateTime.of(2026, 8, 10, 12, 30),
                    LocalDateTime.of(2026, 8, 10, 13, 45));

            assertEquals(target, ticket.target());
            assertTrue(requestBody.get().contains("\"tcpPort\":49103"));
            assertTrue(requestBody.get().contains("\"startTime\":\"2026-08-10T12:30\""));
            assertTrue(requestBody.get().contains("\"endTime\":\"2026-08-10T13:45\""));
        } finally {
            server.stop(0);
        }
    }
}
