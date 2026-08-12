package io.github.jtplatform.signal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpRemoteDeviceInformationClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void queriesByTerminalIdentifierAndDecodesDeviceInformation() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        start(exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200,
                    "{\"terminalId\":\"terminal-1\",\"deviceId\":\"device-1\","
                            + "\"mobileNo\":\"mobile-1\",\"plateNo\":\"plate-1\"}");
        });
        HttpRemoteDeviceInformationClient client = client();

        DeviceInformation information = client.find("terminal-1").orElseThrow();

        assertEquals("terminalId=terminal-1", query.get());
        assertEquals("device-1", information.deviceId());
        assertEquals("mobile-1", information.mobileNo());
        assertEquals("plate-1", information.plateNo());
    }

    @Test
    void notFoundIsDeviceAbsenceRatherThanRemoteFailure() throws Exception {
        start(exchange -> respond(exchange, 404, ""));

        Optional<DeviceInformation> information = client().find("missing");

        assertTrue(information.isEmpty());
    }

    @Test
    void rejectsABusinessResponseThatTriesToReturnAnAccessDecision() throws Exception {
        start(exchange -> respond(exchange, 200,
                "{\"terminalId\":\"terminal-1\",\"deviceId\":\"device-1\",\"allowed\":true}"));

        assertThrows(DeviceInformationUnavailableException.class, () -> client().find("terminal-1"));
    }

    private HttpRemoteDeviceInformationClient client() {
        return new HttpRemoteDeviceInformationClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/devices"),
                Duration.ofSeconds(2),
                Map.of("X-Client", "jt-signal"));
    }

    private void start(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/devices", exchange -> {
            try {
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("jt-signal", exchange.getRequestHeaders().getFirst("X-Client"));
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
