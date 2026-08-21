package io.github.jtconsole.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.AuditRecorder;
import io.github.jtconsole.domain.ConnectionEvent;
import io.github.jtconsole.gateway.DeviceDisconnectClient;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.ingest.RecentEventLog;
import io.github.jtconsole.live.DeviceOwnershipCache;
import io.github.jtconsole.live.LiveBroadcaster;
import io.github.jtconsole.operations.ConnectionDiagnosticsService;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestSchema;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

class DiagnosticsControllerTest {
    private ConnectionEventRepository events;
    private VehicleService vehicles;
    private DiagnosticsController controller;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = database();
        events = new ConnectionEventRepository(JdbcClient.create(dataSource));
        vehicles = mock(VehicleService.class);
        controller = new DiagnosticsController(
                mock(RecentEventLog.class), mock(LiveBroadcaster.class), mock(AuditRecorder.class),
                mock(DeviceOwnershipCache.class), mock(DeviceDisconnectClient.class),
                new ConnectionDiagnosticsService(events, vehicles));
    }

    @Test
    void crossTenantAndUnarchivedDeviceUseTheSameNotFoundMeaning() {
        DataScope tenant = DataScope.tenantWide(2L);
        doThrow(IamException.notFound("车辆不存在"))
                .when(vehicles).requireVisibleDevice(eq("foreign"), any(DataScope.class));
        doThrow(IamException.notFound("车辆不存在"))
                .when(vehicles).requireVisibleDevice(eq("unarchived"), any(DataScope.class));
        events.insertIgnore(event("foreign-event", "foreign", 1L, "2026-08-21T08:00:00.000+08:00"));
        events.insertIgnore(event("unknown-event", "unarchived", null, "2026-08-21T08:00:00.000+08:00"));

        assertThatThrownBy(() -> controller.connectionLog("foreign", null, null, 1, 50, tenant))
                .isInstanceOf(IamException.class).hasMessage("车辆不存在");
        assertThatThrownBy(() -> controller.connectionLog("unarchived", null, null, 1, 50, tenant))
                .isInstanceOf(IamException.class).hasMessage("车辆不存在");
    }

    @Test
    void platformCanSeeUnarchivedConnectionAttempts() {
        events.insertIgnore(event("unknown-event", "unarchived", null, "2026-08-21T08:00:00.000+08:00"));

        ApiResponse<Map<String, Object>> response = controller.connectionLog(
                "unarchived", null, null, 1, 50, DataScope.platform());

        assertThat(response.code()).isEqualTo(ApiResponse.SUCCESS_CODE);
        assertThat(response.data()).containsEntry("total", 1);
        assertThat(timeline(response)).extracting(ConnectionEvent::eventId)
                .containsExactly("unknown-event");
    }

    @Test
    void dateOnlyBoundsCoverTheWholeDayWithoutLeakingTheNextDay() {
        events.insertIgnore(event("first", "device-1", null, "2026-08-21T00:00:00.000+08:00"));
        events.insertIgnore(event("last", "device-1", null, "2026-08-21T23:59:59.999+08:00"));
        events.insertIgnore(event("next", "device-1", null, "2026-08-22T00:00:00.000+08:00"));

        ApiResponse<Map<String, Object>> response = controller.connectionLog(
                "device-1", "2026-08-21", "2026-08-21", 1, 50, DataScope.platform());

        assertThat(timeline(response)).extracting(ConnectionEvent::eventId)
                .containsExactly("last", "first");
    }

    @SuppressWarnings("unchecked")
    private static List<ConnectionEvent> timeline(ApiResponse<Map<String, Object>> response) {
        return (List<ConnectionEvent>) response.data().get("timeline");
    }

    private static ConnectionEvent event(String eventId, String deviceId, Long tenantId, String time) {
        return new ConnectionEvent(0, eventId, deviceId, tenantId, "REGISTER_RESULT", 4,
                "数据库中无该终端", "127.0.0.1:1", 1, time, time);
    }

    private static DataSource database() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + Files.createTempFile("connection-controller-", ".db")
                .toAbsolutePath().toString().replace('\\', '/'));
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TestSchema.migrate(jdbc, new DataSourceTransactionManager(dataSource));
        return dataSource;
    }
}
