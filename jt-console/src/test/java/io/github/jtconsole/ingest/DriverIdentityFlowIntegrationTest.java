package io.github.jtconsole.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.domain.Driver;
import io.github.jtconsole.domain.DriverSession;
import io.github.jtconsole.operations.DriverService;
import io.github.jtconsole.repository.DriverRepository;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestPrincipals;
import io.github.jtconsole.support.TestSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

/**
 * 0702 端到端链路（不含 HTTP/AI 层）：插卡 → 事件入库 + 驾驶区间 → 建档 → 手动绑定 →
 * 当前驾驶员可查。
 */
class DriverIdentityFlowIntegrationTest {

    private DriverRepository drivers;
    private DriverIdentityIngestionService ingestion;
    private DriverService service;

    @BeforeEach
    void createDatabase() throws IOException, SQLException {
        Path database = Files.createTempFile("jt-console-driver-flow-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        JdbcClient jdbc = JdbcClient.create(dataSource);
        PlatformTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, transactions);
        drivers = new DriverRepository(jdbc);
        ingestion = new DriverIdentityIngestionService(drivers);
        service = new DriverService(drivers);
    }

    private static MessageEnvelope cardIn(String eventId, String licenseNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", 0);
        payload.put("cardStatus", 0);
        payload.put("dateTime", "2026-08-11T08:00:00");
        payload.put("name", "张三");
        payload.put("licenseNo", licenseNo);
        payload.put("institution", "某发证机关");
        payload.put("licenseValidPeriod", "20260830");
        payload.put("idCard", "110101199001011234");
        return new MessageEnvelope(eventId, "device-1", 0x0702L, 1, "2019",
                "2026-08-11T08:00:00Z", "signal-1", "other", payload);
    }

    @Test
    void cardInThenCreateDriverThenBindYieldsCurrentDriver() {
        // 1. 插卡（未建档）：事件入库 + 驾驶区间，driver_id 为空
        assertThat(ingestion.handleIfDriverIdentity(cardIn("e-1", "LIC-1"))).isTrue();
        assertThat(drivers.searchIdentityEvents("device-1", null, null, null, null,
                DataScope.platform(), 1, 10)).hasSize(1);
        DriverSession unregistered = drivers.findOpenSession("device-1").orElseThrow();
        assertThat(unregistered.driverId()).isNull();
        assertThat(unregistered.driverName()).isEqualTo("张三");

        // 2. 建档
        AuthorizedPrincipal admin = TestPrincipals.tenantAdmin(7L, 1L);
        Driver driver = service.create(admin, new Driver(null, "张三", "110101199001011234",
                "LIC-1", "某发证机关", "2026-08-30", "13800000000", null, null, 1L, null, null));

        // 3. 手动绑定：当前驾驶员落到刚建档的司机
        service.manualBind("device-1", driver.id(), DataScope.tenantWide(1L));
        DriverSession bound = drivers.findOpenSession("device-1").orElseThrow();
        assertThat(bound.driverId()).isEqualTo(driver.id());
        assertThat(bound.source()).isEqualTo(DriverSession.SOURCE_MANUAL);
    }
}
