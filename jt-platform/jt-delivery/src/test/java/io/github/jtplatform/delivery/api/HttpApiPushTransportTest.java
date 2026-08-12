package io.github.jtplatform.delivery.api;

import static io.github.jtplatform.delivery.TestEnvelopes.envelope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.jtplatform.delivery.codec.JacksonMessageEnvelopeEncoder;
import io.github.jtplatform.delivery.model.MessageType;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HttpApiPushTransportTest {
    @Test
    void postsUnifiedJsonWithIdempotencyKey() throws Exception {
        LinkedBlockingQueue<String> bodies = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<String> keys = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/events", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpApiPushTransport transport = new HttpApiPushTransport(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/events"),
                    Duration.ofSeconds(1), new JacksonMessageEnvelopeEncoder());
            transport.push(envelope("device-1", 7, MessageType.ALARM)).toCompletableFuture().join();

            assertEquals("event-device-1-7", keys.poll(1, TimeUnit.SECONDS));
            String body = bodies.poll(1, TimeUnit.SECONDS);
            assertTrue(body.contains("\"deviceId\":\"device-1\""));
            assertTrue(body.contains("\"type\":\"alarm\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void treatsNonSuccessStatusAsDeliveryFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/events", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpApiPushTransport transport = new HttpApiPushTransport(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/events"),
                    Duration.ofSeconds(1), new JacksonMessageEnvelopeEncoder());
            CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                    () -> transport.push(envelope("device-1", 7, MessageType.ALARM)).toCompletableFuture().join());
            ApiPushException apiFailure = assertInstanceOf(ApiPushException.class, failure.getCause());
            assertEquals(503, apiFailure.statusCode());
        } finally {
            server.stop(0);
        }
    }
}
