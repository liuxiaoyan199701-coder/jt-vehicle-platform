package io.github.jtconsole.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.jtconsole.config.ConsoleProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 设备日志库：与业务库物理隔离的第二个 SQLite 文件。
 *
 * <p><b>为什么不注册成第二个 {@code DataSource} bean</b>——那会一次性打破两件事：主库的
 * 自动装配在出现多个候选时退出，迁移 runner 注入的无限定符 {@code JdbcClient} 也变得有歧义。
 * 代价远超收益，于是这里自建一个小连接池并只对外暴露 {@link #jdbc()}，Spring 那侧完全无感。
 *
 * <p><b>为什么不复用业务库</b>——SQLite 同一时刻只有一个写事务。报文日志是持续高频写，
 * 与业务写抢锁会体现为接口变慢；保留期清理的大批量删除还会放大这一点。分成两个文件后
 * 互不干扰，撑爆也只撑爆日志文件。
 *
 * <p>迁移自带：按本库自己的 {@code PRAGMA user_version} 建表建索引，版本序列独立从 1 起，
 * 与业务库的 V1..V18 毫无关系。可重入，重启不重复执行。
 */
@Component
public class DeviceLogDatabase implements DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceLogDatabase.class);
    /** 本库的最新结构版本。与业务库的 user_version 各记各的。 */
    static final int SCHEMA_VERSION = 1;

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    @Autowired
    public DeviceLogDatabase(ConsoleProperties properties) {
        this(properties.getDeviceLog().getDb());
    }

    DeviceLogDatabase(Path databaseFile) {
        this.dataSource = pool(databaseFile);
        this.jdbc = JdbcClient.create(dataSource);
        migrate();
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** 当前结构版本。供启动核查与测试断言使用。 */
    public int schemaVersion() {
        Integer version = jdbc.sql("PRAGMA user_version").query(Integer.class).single();
        return version == null ? 0 : version;
    }

    @Override
    public void destroy() {
        dataSource.close();
    }

    private static HikariDataSource pool(Path databaseFile) {
        Path absolute = databaseFile.toAbsolutePath();
        createParentDirectory(absolute);
        HikariConfig config = new HikariConfig();
        // WAL 让读写并发；busy_timeout 让瞬时锁冲突等待而不是直接抛错。
        config.setJdbcUrl("jdbc:sqlite:" + absolute.toString().replace('\\', '/')
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL");
        config.setDriverClassName("org.sqlite.JDBC");
        // 写是单线程串行的，池开大只会加剧锁竞争；2 条连接够读写各一。
        config.setMaximumPoolSize(2);
        config.setPoolName("jt-console-device-log-pool");
        return new HikariDataSource(config);
    }

    private static void createParentDirectory(Path databaseFile) {
        Path parent = databaseFile.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建设备日志库目录：" + parent, failure);
        }
    }

    private void migrate() {
        int current = schemaVersion();
        if (current >= SCHEMA_VERSION) {
            return;
        }
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS device_log (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_id     TEXT NOT NULL UNIQUE,
                    device_id    TEXT NOT NULL,
                    tenant_id    INTEGER,
                    direction    TEXT NOT NULL,
                    msg_id       INTEGER,
                    serial_no    INTEGER,
                    log_time     TEXT NOT NULL,
                    summary      TEXT,
                    raw_hex      TEXT,
                    parsed_json  TEXT,
                    decode_error INTEGER NOT NULL DEFAULT 0,
                    truncated    INTEGER NOT NULL DEFAULT 0,
                    instance_id  TEXT
                )
                """).update();
        // 页面与 AI 的每一次查询都带设备号，这是唯一的主力索引。
        jdbc.sql("""
                CREATE INDEX IF NOT EXISTS idx_device_log_device_time
                ON device_log (device_id, log_time DESC)
                """).update();
        // 保留期清理按时间扫，没有这条索引会退化成全表扫。
        jdbc.sql("CREATE INDEX IF NOT EXISTS idx_device_log_time ON device_log (log_time)").update();
        // user_version 不支持参数绑定；版本号是代码常量，内联安全。
        jdbc.sql("PRAGMA user_version = " + SCHEMA_VERSION).update();
        LOGGER.info("设备日志库结构已就绪，版本 v{}", SCHEMA_VERSION);
    }
}
