package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.domain.Driver;
import io.github.jtconsole.domain.DriverSession;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.support.TestSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

/** 司机仓储的隔离与区间语义。 */
class DriverRepositoryTest {

    private DriverRepository drivers;

    @BeforeEach
    void createDatabase() throws IOException, SQLException {
        Path database = Files.createTempFile("jt-console-driver-", ".db");
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
    }

    private Driver insert(String name, String licenseNo, Long tenantId, Long departmentId) {
        long id = drivers.insert(new Driver(null, name, "110101199001011234", licenseNo,
                "机构", "2026-12-31", "13800000000", null, departmentId, tenantId, null, null));
        return drivers.findById(id, DataScope.tenantWide(tenantId)).orElseThrow();
    }

    @Test
    void crossTenantIsNotVisible() {
        Driver driver = insert("张三", "LIC-1", 1L, null);

        assertThat(drivers.findById(driver.id(), DataScope.tenantWide(2L))).isEmpty();
        assertThat(drivers.findById(driver.id(), DataScope.tenantWide(1L))).isPresent();
        assertThat(drivers.findByLicenseNo("LIC-1", DataScope.tenantWide(2L))).isEmpty();
    }

    @Test
    void departmentScopeNarrowsVisibility() {
        Driver inDept = insert("张三", "LIC-1", 1L, 1L);
        Driver other = insert("李四", "LIC-2", 1L, 2L);

        List<Driver> dept1 = drivers.search(null, null, DataScope.departments(1L, Set.of(1L)), 1, 20);
        assertThat(dept1).extracting(Driver::id).containsExactly(inDept.id());

        List<Driver> all = drivers.search(null, null, DataScope.tenantWide(1L), 1, 20);
        assertThat(all).extracting(Driver::id).containsExactlyInAnyOrder(inDept.id(), other.id());
    }

    @Test
    void manualAndCardSessionsCoexistInSameTable() {
        Driver a = insert("张三", "LIC-1", 1L, null);
        Driver b = insert("李四", "LIC-2", 1L, null);

        drivers.openSession("device-1", a.id(), a.name(), a.licenseNo(), "t1", DriverSession.SOURCE_CARD);
        assertThat(drivers.findOpenSession("device-1")).isPresent()
                .get().extracting(DriverSession::source).isEqualTo("CARD");

        // 新插卡顶替：先结束旧区间再开新的
        drivers.closeOpenSession("device-1", "t2");
        drivers.openSession("device-1", b.id(), b.name(), b.licenseNo(), "t2", DriverSession.SOURCE_MANUAL);
        DriverSession current = drivers.findOpenSession("device-1").orElseThrow();
        assertThat(current.source()).isEqualTo("MANUAL");
        assertThat(current.driverId()).isEqualTo(b.id());
    }

    @Test
    void expiringQueryFiltersByPeriod() {
        insert("已过期", "LIC-1", 1L, null);
        Driver expiring = insert("即将到期", "LIC-2", 1L, null);

        // 直接改库把「即将到期」的证件有效期设为 30 天内
        drivers.update(expiring.id(), new Driver(expiring.id(), expiring.name(), expiring.idCard(),
                expiring.licenseNo(), expiring.institution(), "2026-01-01", expiring.phone(),
                expiring.remark(), expiring.departmentId(), expiring.tenantId(), null, null));

        List<Driver> result = drivers.findExpiringBy("2026-12-31", DataScope.tenantWide(1L));
        assertThat(result).extracting(Driver::name).contains("已过期", "即将到期");
    }
}
