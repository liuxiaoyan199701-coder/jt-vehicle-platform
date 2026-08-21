package io.github.jtconsole.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.jtconsole.domain.LiveStatus;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.security.DataScope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class RecordingProxyControllerTest {

    private final AtomicInteger platformRequests = new AtomicInteger();
    private final AtomicInteger commandRequests = new AtomicInteger();
    private final AtomicReference<String> commandBody = new AtomicReference<>();
    private final AtomicReference<String> platformUri = new AtomicReference<>();
    private final AtomicReference<Response> commandResponse =
            new AtomicReference<>(new Response(200, "{\"items\":[]}"));

    private HttpServer server;
    private VehicleService vehicles;
    private StatusRepository statuses;
    private RecordingProxyController controller;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/recordings/search", exchange -> {
            platformRequests.incrementAndGet();
            platformUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200,
                    "[{\"startTime\":\"2026-08-20T00:00:00Z\","
                            + "\"endTime\":\"2026-08-20T00:01:00Z\"}]");
        });
        server.createContext("/device/9205", exchange -> {
            commandRequests.incrementAndGet();
            commandBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Response response = commandResponse.get();
            respond(exchange, response.status(), response.body());
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RestClient gateway = RestClient.builder().baseUrl(baseUrl).build();
        vehicles = mock(VehicleService.class);
        statuses = mock(StatusRepository.class);
        controller = new RecordingProxyController(gateway, gateway, vehicles, statuses);
        when(vehicles.requireVisibleDevice("device-1", DataScope.platform())).thenReturn("device-1");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void offlineDeviceDoesNotSend9205AndExplainsAvailability() {
        when(statuses.findLiveByDevice("device-1", DataScope.platform()))
                .thenReturn(Optional.of(status(false)));

        var response = search();

        assertThat(response.data().platform().available()).isTrue();
        assertThat(response.data().platform().segments()).singleElement().satisfies(segment -> {
            assertThat(segment.channel()).isEqualTo(1);
            assertThat(segment.streamKind()).isEqualTo("main");
            assertThat(segment.source()).isEqualTo("platform");
        });
        assertThat(response.data().device().available()).isFalse();
        assertThat(response.data().device().reason()).isEqualTo("设备离线");
        assertThat(commandRequests).hasValue(0);
    }

    @Test
    void outOfScopeDeviceUsesNotFoundAndMakesNoGatewayRequest() {
        DataScope scope = DataScope.tenantWide(9);
        when(vehicles.requireVisibleDevice("device-1", scope))
                .thenThrow(IamException.notFound("车辆不存在"));

        assertThatThrownBy(() -> controller.search(
                "device-1", 1, "main", start(), end(), scope))
                .isInstanceOf(IamException.class)
                .hasMessageContaining("车辆不存在");
        assertThat(platformRequests).hasValue(0);
        assertThat(commandRequests).hasValue(0);
        verifyNoInteractions(statuses);
    }

    @Test
    void timeoutDoesNotClaimThatTheDeviceHasNoRecording() {
        when(statuses.findLiveByDevice("device-1", DataScope.platform()))
                .thenReturn(Optional.of(status(true)));
        commandResponse.set(new Response(502,
                "{\"code\":\"SIGNAL_COMMAND_FAILED\","
                        + "\"message\":\"Device response timed out: device-1\"}"));

        var device = search().data().device();

        assertThat(device.available()).isFalse();
        assertThat(device.reason()).isEqualTo("设备未在 10 秒内返回资源列表");
        assertThat(device.reason()).doesNotContain("没有录像").doesNotContain("无录像");
    }

    @Test
    void requestUsesDeviceLocalBcdAndResponseGetsExplicitEastEightOffset() {
        when(statuses.findLiveByDevice("device-1", DataScope.platform()))
                .thenReturn(Optional.of(status(true)));
        commandResponse.set(new Response(200, """
                {"items":[{"channelNo":1,
                  "startTime":"2026-08-20T08:00:00",
                  "endTime":"2026-08-20T08:01:00",
                  "warnBit":0,"mediaType":2,"streamType":1,"storageType":1,"size":1024}]}
                """));

        var device = search().data().device();

        assertThat(commandBody.get()).contains("\"startTime\":\"260820080000\"")
                .contains("\"endTime\":\"260820090000\"");
        assertThat(device.resources()).singleElement().satisfies(resource -> {
            assertThat(resource.startTime()).isEqualTo("2026-08-20T08:00:00.000+08:00");
            assertThat(resource.endTime()).isEqualTo("2026-08-20T08:01:00.000+08:00");
        });
    }

    @Test
    void alarmWindowQueriesOnlyPlatformWithFiveMinuteBounds() {
        var response = controller.around(
                "device-1", "2026-08-20 08:00:00", 1, DataScope.platform());

        assertThat(response.data()).hasSize(1);
        assertThat(platformUri.get())
                .contains("startTime=2026-08-19T23:55:00Z")
                .contains("endTime=2026-08-20T00:05:00Z");
        assertThat(commandRequests).hasValue(0);
        verifyNoInteractions(statuses);
    }

    @Test
    void rejectsRangesLongerThanSevenDaysBeforeAnyLookup() {
        assertThatThrownBy(() -> controller.search(
                "device-1", 1, "main", start(), start().plusSeconds(8 * 86_400L),
                DataScope.platform()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能超过 7 天");
        assertThat(platformRequests).hasValue(0);
        assertThat(commandRequests).hasValue(0);
        verifyNoInteractions(statuses);
    }

    private io.github.jtconsole.api.ApiResponse<RecordingProxyController.RecordingSearchResult> search() {
        return controller.search(
                "device-1", 1, "main", start(), end(), DataScope.platform());
    }

    private static Instant start() {
        return Instant.parse("2026-08-20T00:00:00Z");
    }

    private static Instant end() {
        return Instant.parse("2026-08-20T01:00:00Z");
    }

    private static LiveStatus status(boolean online) {
        return new LiveStatus("device-1", "粤A1", online, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Response(int status, String body) {}
}
