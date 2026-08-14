package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.domain.Department;
import io.github.jtconsole.domain.Position;
import io.github.jtconsole.support.TestSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

/**
 * 组织架构的列表查询。
 *
 * <p>这两个查询把列清单常量与后半段 SQL 拼在一起，而后半段以 {@code FROM} 开头且不带前导空格——
 * 分隔全靠常量末尾的换行。曾经 {@code PositionRepository.COLUMNS} 写成单行字面量，拼出
 * {@code updated_atFROM position}，岗位页一打开就 500。这类拼接错误只有真正执行 SQL 才暴露得出来，
 * 所以这里对着真实 SQLite 跑。
 */
class OrganizationRepositoryTest {

    private static final long TENANT = 1L;

    private JdbcClient jdbc;
    private DepartmentRepository departments;
    private PositionRepository positions;

    @BeforeEach
    void createDatabase() throws IOException, SQLException {
        Path database = Files.createTempFile("jt-console-org-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        jdbc = JdbcClient.create(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, new DataSourceTransactionManager(dataSource));
        departments = new DepartmentRepository(jdbc);
        positions = new PositionRepository(jdbc);
    }

    @Test
    void listingPositionsOfATenantExecutesAndOrdersBySortThenId() {
        long second = positions.insert(position("调度员", 2));
        long first = positions.insert(position("驾驶员", 1));

        List<Position> found = positions.findByTenant(TENANT);

        assertThat(found).extracting(Position::id).containsExactly(first, second);
        assertThat(found).extracting(Position::name).containsExactly("驾驶员", "调度员");
        assertThat(found.getFirst().tenantId()).isEqualTo(TENANT);
        assertThat(found.getFirst().createdAt()).isNotBlank();
    }

    @Test
    void findingASinglePositionSelectsEveryColumn() {
        long id = positions.insert(position("安全员", 3));

        Position found = positions.findById(id).orElseThrow();

        assertThat(found.name()).isEqualTo("安全员");
        assertThat(found.sortOrder()).isEqualTo(3);
        assertThat(found.remark()).isEqualTo("备注");
        assertThat(found.updatedAt()).isNotBlank();
    }

    @Test
    void listingDepartmentsOfATenantExecutesAndOrdersBySortThenId() {
        long second = departments.insert(department("运营部", 2));
        long first = departments.insert(department("车队一部", 1));

        List<Department> found = departments.findByTenant(TENANT);

        assertThat(found).extracting(Department::id).containsExactly(first, second);
        assertThat(found).extracting(Department::name).containsExactly("车队一部", "运营部");
        assertThat(found.getFirst().enabled()).isTrue();
    }

    @Test
    void listsAreScopedToTheirOwnTenant() {
        positions.insert(position("本租户岗位", 1));
        departments.insert(department("本租户部门", 1));

        assertThat(positions.findByTenant(TENANT + 99)).isEmpty();
        assertThat(departments.findByTenant(TENANT + 99)).isEmpty();
        assertThat(positions.findByTenant(TENANT)).hasSize(1);
        assertThat(departments.findByTenant(TENANT)).hasSize(1);
    }

    private static Position position(String name, int sortOrder) {
        String now = "2026-08-14T00:00:00Z";
        return new Position(0, TENANT, name, sortOrder, "备注", now, now);
    }

    private static Department department(String name, int sortOrder) {
        String now = "2026-08-14T00:00:00Z";
        return new Department(0, TENANT, null, name, sortOrder, true, now, now);
    }
}
