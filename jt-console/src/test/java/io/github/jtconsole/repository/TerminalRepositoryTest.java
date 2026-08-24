package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.domain.Terminal;
import io.github.jtconsole.support.TestSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

class TerminalRepositoryTest {

    private static final String DEVICE = "138000000000";
    private static final String EARLIER = "2026-08-24T09:00:00.000+08:00";
    private static final String LATER = "2026-08-24T10:00:00.000+08:00";

    private TerminalRepository terminals;

    @BeforeEach
    void createDatabase() throws Exception {
        Path database = Files.createTempFile("jt-console-terminal-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        JdbcClient jdbc = JdbcClient.create(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, new DataSourceTransactionManager(dataSource));
        terminals = new TerminalRepository(jdbc);
    }

    @Test
    void aFirstRegistrationLandsWithEverythingTheTerminalReported() {
        terminals.upsert(registration(EARLIER));

        Terminal stored = terminals.findById(DEVICE).orElseThrow();
        assertThat(stored.terminalId()).isEqualTo("1380000");
        assertThat(stored.makerId()).isEqualTo("JT");
        assertThat(stored.deviceModel()).isEqualTo("SIMULATOR");
        assertThat(stored.provinceId()).isEqualTo(31);
        assertThat(stored.cityId()).isEqualTo(100);
        assertThat(stored.reportedPlate()).isEqualTo("TEST001");
        assertThat(stored.reportedColor()).isEqualTo(1);
        assertThat(stored.firstSeenAt()).isEqualTo(EARLIER);
        assertThat(stored.lastSeenAt()).isEqualTo(EARLIER);
    }

    /** 「这台终端第一次出现是什么时候」被后来的连接改写就永久丢了。 */
    @Test
    void aLaterRegistrationMovesLastSeenButNeverFirstSeen() {
        terminals.upsert(registration(EARLIER));

        terminals.upsert(registration(LATER));

        Terminal stored = terminals.findById(DEVICE).orElseThrow();
        assertThat(stored.firstSeenAt()).isEqualTo(EARLIER);
        assertThat(stored.lastSeenAt()).isEqualTo(LATER);
    }

    /** 投递会乱序（重投、多实例）；无条件覆盖会让迟到的旧事件把时间写回过去。 */
    @Test
    void anOutOfOrderRedeliveryDoesNotRewindLastSeen() {
        terminals.upsert(registration(LATER));

        terminals.upsert(registration(EARLIER));

        assertThat(terminals.findById(DEVICE).orElseThrow().lastSeenAt()).isEqualTo(LATER);
    }

    /** 0x0102 鉴权信封没有型号、制造商这些字段，一律覆盖会把注册时拿到的信息抹成空。 */
    @Test
    void anAuthenticationWithoutReportedFieldsKeepsWhatRegistrationLearned() {
        terminals.upsert(registration(EARLIER));

        terminals.upsert(new Terminal(DEVICE, null, null, null, null, null, null, null,
                "JT/T 808-2019/1", LATER, LATER, "鉴权成功", null));

        Terminal stored = terminals.findById(DEVICE).orElseThrow();
        assertThat(stored.terminalId()).isEqualTo("1380000");
        assertThat(stored.makerId()).isEqualTo("JT");
        assertThat(stored.deviceModel()).isEqualTo("SIMULATOR");
        assertThat(stored.reportedPlate()).isEqualTo("TEST001");
        assertThat(stored.lastSeenAt()).isEqualTo(LATER);
        assertThat(stored.lastResult()).isEqualTo("鉴权成功");
    }

    /** 迟到的旧事件不能把「最近一次结局」也带回去，否则失败会盖住其后的成功。 */
    @Test
    void anOutOfOrderRedeliveryDoesNotRewindTheLastResultEither() {
        terminals.upsert(withResult(LATER, "鉴权成功"));

        terminals.upsert(withResult(EARLIER, "注册失败：数据库中无该终端"));

        assertThat(terminals.findById(DEVICE).orElseThrow().lastResult()).isEqualTo("鉴权成功");
    }

    @Test
    void anUnknownTerminalIsSimplyAbsent() {
        assertThat(terminals.findById("139999999999")).isEmpty();
    }

    private static Terminal registration(String seenAt) {
        return new Terminal(DEVICE, "1380000", "JT", "SIMULATOR", 31, 100, "TEST001", 1,
                "JT/T 808-2019/1", seenAt, seenAt, "注册成功", null);
    }

    private static Terminal withResult(String seenAt, String result) {
        return new Terminal(DEVICE, "1380000", "JT", "SIMULATOR", 31, 100, "TEST001", 1,
                "JT/T 808-2019/1", seenAt, seenAt, result, null);
    }
}
