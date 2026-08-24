package io.github.jtconsole.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jtconsole.domain.Terminal;
import io.github.jtconsole.domain.TerminalPage;
import io.github.jtconsole.domain.TerminalSummary;
import io.github.jtconsole.iam.IamException;
import io.github.jtconsole.operations.TerminalQueryService;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.TerminalQueryRepository;
import io.github.jtconsole.repository.TerminalRepository;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

class TerminalControllerTest {

    private static final String ARCHIVED = "138000000001";
    private static final String STRANGER = "138000000002";
    private static final String OTHER_TENANT = "138000000003";
    private static final String SEEN_AT = "2026-08-24T10:00:00.000+08:00";

    private JdbcClient jdbc;
    private TerminalRepository terminals;
    private TerminalController controller;

    @BeforeEach
    void setUp() throws Exception {
        Path database = Files.createTempFile("jt-console-terminal-web-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        jdbc = JdbcClient.create(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, new DataSourceTransactionManager(dataSource));
        jdbc.sql("""
                INSERT INTO tenant (id, code, name, status, plan_id, created_at, updated_at)
                SELECT 2, 'tenant-2', '租户二', 'ACTIVE', id, '2026-01-01', '2026-01-01'
                FROM plan ORDER BY id LIMIT 1
                """).update();
        vehicle(ARCHIVED, "京A00001", 1L);
        vehicle(OTHER_TENANT, "京B00002", 2L);

        terminals = new TerminalRepository(jdbc);
        terminals.upsert(terminal(ARCHIVED, "1380001", "京A99999"));
        terminals.upsert(terminal(STRANGER, "1380002", "工地测试车"));
        terminals.upsert(terminal(OTHER_TENANT, "1380003", "京B00002"));

        controller = new TerminalController(new TerminalQueryService(
                new TerminalQueryRepository(jdbc), terminals,
                org.mockito.Mockito.mock(VehicleService.class)));
    }

    @Test
    void aPlatformAdminSeesEveryTerminalIncludingTheUnarchivedOnes() {
        TerminalPage page = search(DataScope.platform(), null);

        assertThat(page.total()).isEqualTo(3);
        assertThat(devices(page)).containsExactlyInAnyOrder(ARCHIVED, STRANGER, OTHER_TENANT);
    }

    /** 未建档终端没有归属；让租户看到等于泄露其他租户或施工方的设备。 */
    @Test
    void aTenantNeverSeesUnarchivedTerminalsNorOtherTenants() {
        TerminalPage page = search(DataScope.tenantWide(1L), null);

        assertThat(devices(page)).containsExactly(ARCHIVED);
    }

    @Test
    void filteringByArchivedStatePicksOutTheOnesStillWaitingForAProfile() {
        assertThat(devices(search(DataScope.platform(), false))).containsExactly(STRANGER);
        assertThat(devices(search(DataScope.platform(), true)))
                .containsExactlyInAnyOrder(ARCHIVED, OTHER_TENANT);
    }

    /** 自报车牌与档案车牌是两回事，两列都要在——合并了就无从判断眼前这个车牌可不可信。 */
    @Test
    void theReportedPlateAndTheProfiledPlateAreBothPresentAndCanDiffer() {
        TerminalSummary row = search(DataScope.platform(), null).items().stream()
                .filter(item -> ARCHIVED.equals(item.deviceId())).findFirst().orElseThrow();

        assertThat(row.reportedPlate()).isEqualTo("京A99999");
        assertThat(row.plateNo()).isEqualTo("京A00001");
        assertThat(row.archived()).isTrue();
        assertThat(row.terminalId()).isEqualTo("1380001");
    }

    @Test
    void anUnarchivedTerminalCarriesNoProfileAndNoTenant() {
        TerminalSummary row = search(DataScope.platform(), false).items().getFirst();

        assertThat(row.archived()).isFalse();
        assertThat(row.plateNo()).isNull();
        assertThat(row.tenantId()).isNull();
        assertThat(row.reportedPlate()).isEqualTo("工地测试车");
    }

    @Test
    void keywordMatchesDeviceTerminalIdAndBothPlates() {
        assertThat(devices(searchKeyword("1380002"))).containsExactly(STRANGER);
        assertThat(devices(searchKeyword("工地"))).containsExactly(STRANGER);
        assertThat(devices(searchKeyword("京A00001"))).containsExactly(ARCHIVED);
        assertThat(devices(searchKeyword("京A99999"))).containsExactly(ARCHIVED);
    }

    @Test
    void onlineStateComesFromTheLiveStatusNotFromTheLedger() {
        jdbc.sql("""
                INSERT INTO device_status (device_id, online, last_seen_at, updated_at)
                VALUES (?, 1, ?, ?)
                """).param(ARCHIVED).param(SEEN_AT).param(SEEN_AT).update();

        assertThat(devices(controller.search(null, null, true, null, null, 1, 20,
                DataScope.platform()).data())).containsExactly(ARCHIVED);
        assertThat(devices(controller.search(null, null, false, null, null, 1, 20,
                DataScope.platform()).data())).containsExactlyInAnyOrder(STRANGER, OTHER_TENANT);
    }

    @Test
    void malformedPagingIsRejectedInsteadOfSilentlyClamped() {
        assertThatThrownBy(() -> search(DataScope.platform(), null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search(DataScope.platform(), null, 1, 201))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void archivingATerminalThatWasNeverSeenIsRefused() {
        assertThatThrownBy(() -> controller.archive("139999999999", null, null))
                .isInstanceOf(IamException.class).hasMessage("终端不存在");
    }

    private TerminalPage search(DataScope scope, Boolean archived) {
        return search(scope, archived, 1, 20);
    }

    private TerminalPage search(DataScope scope, Boolean archived, int page, int pageSize) {
        return controller.search(null, archived, null, null, null, page, pageSize, scope).data();
    }

    private TerminalPage searchKeyword(String keyword) {
        return controller.search(keyword, null, null, null, null, 1, 20, DataScope.platform()).data();
    }

    private static List<String> devices(TerminalPage page) {
        return page.items().stream().map(TerminalSummary::deviceId).toList();
    }

    private void vehicle(String deviceId, String plateNo, long tenantId) {
        jdbc.sql("""
                INSERT INTO vehicle (device_id, plate_no, channel_count, tenant_id,
                                     created_at, updated_at)
                VALUES (?, ?, 1, ?, '2026-01-01', '2026-01-01')
                """).param(deviceId).param(plateNo).param(tenantId).update();
    }

    private static Terminal terminal(String deviceId, String terminalId, String reportedPlate) {
        return new Terminal(deviceId, terminalId, "JT", "SIMULATOR", 31, 100,
                reportedPlate, 1, "JT/T 808-2019/1", SEEN_AT, SEEN_AT, "注册", null);
    }
}
