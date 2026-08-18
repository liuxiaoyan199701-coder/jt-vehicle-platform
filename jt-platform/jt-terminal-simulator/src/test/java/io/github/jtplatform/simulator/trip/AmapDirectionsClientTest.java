package io.github.jtplatform.simulator.trip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 用本地假 HTTP 服务器回放响应，不联网。风格对齐 {@code SignalClientTest} 里的假信令服务器。
 */
class AmapDirectionsClientTest {

    private static final GeoPoint ORIGIN = new GeoPoint(31.230416D, 121.473701D);
    private static final GeoPoint DESTINATION = new GeoPoint(31.239692D, 121.499809D);

    private HttpServer server;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<Integer> statusCode = new AtomicReference<>(200);
    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/driving", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /**
     * 本类里价值最高的一条断言：请求里坐标必须是**经度在前**。
     *
     * <p>写反了不会报任何错，只会安静地规划出一条在地球另一侧的路线——不断言就永远发现不了。
     */
    @Test
    void sendsLongitudeBeforeLatitude() throws Exception {
        body.set(successBody());

        client().drivingRoute(ORIGIN, DESTINATION, "test-key");

        assertTrue(lastQuery.get().contains("origin=121.473701,31.230416"),
                "起点坐标顺序不对：" + lastQuery.get());
        assertTrue(lastQuery.get().contains("destination=121.499809,31.239692"),
                "终点坐标顺序不对：" + lastQuery.get());
        assertTrue(lastQuery.get().contains("key=test-key"));
    }

    /**
     * 坐标格式化必须固定区域设置：中文 Windows 下 {@code %f} 会输出逗号小数点，
     * URL 会被切成多个参数，请求整个作废。
     */
    @Test
    void formatsCoordinatesWithADotRegardlessOfSystemLocale() throws Exception {
        body.set(successBody());

        client().drivingRoute(ORIGIN, DESTINATION, "test-key");

        assertTrue(lastQuery.get().contains("121.473701"), lastQuery.get());
        assertTrue(lastQuery.get().contains("origin=121.473701,31.230416"), lastQuery.get());
    }

    @Test
    void parsesPolylinesAndDropsTheSeamPointBetweenSteps() throws Exception {
        body.set("""
                {"status":"1","info":"OK","route":{"paths":[{"steps":[
                  {"polyline":"121.4737,31.2304;121.4800,31.2320;121.4850,31.2340"},
                  {"polyline":"121.4850,31.2340;121.4900,31.2360"}
                ]}]}}
                """);

        List<GeoPoint> points = client().drivingRoute(ORIGIN, DESTINATION, "test-key");

        assertEquals(4, points.size());
        assertEquals(121.4737D, points.getFirst().lng(), 1e-9D);
        assertEquals(31.2360D, points.getLast().lat(), 1e-9D);
    }

    @Test
    void reportsTheServiceMessageWhenStatusIsNotOne() {
        body.set("{\"status\":\"0\",\"info\":\"ENGINE_RESPONSE_DATA_ERROR\",\"infocode\":\"20800\"}");

        AmapException failure = assertThrows(AmapException.class,
                () -> client().drivingRoute(ORIGIN, DESTINATION, "test-key"));

        assertTrue(failure.getMessage().contains("ENGINE_RESPONSE_DATA_ERROR"),
                failure.getMessage());
    }

    /** 密钥类型选错是最常见的一种失败，提示必须说清该去申请哪一种 Key。 */
    @Test
    void explainsHowToFixAWrongKeyType() {
        body.set("{\"status\":\"0\",\"info\":\"USERKEY_PLAT_NOMATCH\",\"infocode\":\"10009\"}");

        AmapException failure = assertThrows(AmapException.class,
                () -> client().drivingRoute(ORIGIN, DESTINATION, "test-key"));

        assertTrue(failure.getMessage().contains("Web 服务"), failure.getMessage());
    }

    @Test
    void rejectsAResponseWithNoRoutes() {
        body.set("{\"status\":\"1\",\"info\":\"OK\",\"route\":{\"paths\":[]}}");

        AmapException failure = assertThrows(AmapException.class,
                () -> client().drivingRoute(ORIGIN, DESTINATION, "test-key"));

        assertTrue(failure.getMessage().contains("未返回任何路线"), failure.getMessage());
    }

    /** 代理或门户拦截时返回的是 HTML 登录页，错误信息应当带上开头一段，否则无从判断。 */
    @Test
    void rejectsAResponseThatIsNotJson() {
        body.set("<html><head><title>Login</title></head><body>请先登录</body></html>");

        AmapException failure = assertThrows(AmapException.class,
                () -> client().drivingRoute(ORIGIN, DESTINATION, "test-key"));

        assertTrue(failure.getMessage().contains("不是 JSON"), failure.getMessage());
        assertTrue(failure.getMessage().contains("<html>"), failure.getMessage());
    }

    @Test
    void reportsTimeoutsDistinctlyFromConnectionFailures() {
        body.set(successBody());
        delay.set(Duration.ofSeconds(2));

        AmapException failure = assertThrows(AmapException.class,
                () -> new AmapDirectionsClient(endpoint(), Duration.ofMillis(300))
                        .drivingRoute(ORIGIN, DESTINATION, "test-key"));

        assertTrue(failure.getMessage().contains("超时"), failure.getMessage());
    }

    @Test
    void reportsNonOkHttpStatus() {
        statusCode.set(502);
        body.set("upstream down");

        AmapException failure = assertThrows(AmapException.class,
                () -> client().drivingRoute(ORIGIN, DESTINATION, "test-key"));

        assertTrue(failure.getMessage().contains("502"), failure.getMessage());
    }

    @Test
    void refusesToCallTheServiceWithoutAKey() {
        AmapException failure = assertThrows(AmapException.class,
                () -> client().drivingRoute(ORIGIN, DESTINATION, "  "));

        assertTrue(failure.getMessage().contains("未配置"), failure.getMessage());
        // 没有密钥时根本不该发出请求——省一次注定失败的往返。
        assertNull(lastQuery.get(), "不该发出请求");
    }

    private AmapDirectionsClient client() {
        return new AmapDirectionsClient(endpoint());
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/driving";
    }

    private static String successBody() {
        return """
                {"status":"1","info":"OK","route":{"paths":[{"steps":[
                  {"polyline":"121.4737,31.2304;121.4800,31.2320;121.4900,31.2360"}
                ]}]}}
                """;
    }

    private void respond(HttpExchange exchange) throws IOException {
        lastQuery.set(exchange.getRequestURI().getQuery());
        Duration pause = delay.get();
        if (!pause.isZero()) {
            try {
                Thread.sleep(pause.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode.get(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
