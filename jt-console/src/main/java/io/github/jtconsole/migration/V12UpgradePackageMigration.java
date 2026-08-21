package io.github.jtconsole.migration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** v12：建升级包元数据表。包文件落盘，库里只存引用与摘要。 */
@Component
public class V12UpgradePackageMigration implements SchemaMigration {

    @Override
    public int version() {
        return 12;
    }

    @Override
    public String description() {
        return "建 upgrade_package 表支持 OTA 升级包管理";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS upgrade_package (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            name        TEXT NOT NULL,
                            version     TEXT NOT NULL,
                            maker_id    TEXT NOT NULL,
                            file_name   TEXT NOT NULL,
                            file_path   TEXT NOT NULL,
                            size_bytes  INTEGER NOT NULL,
                            sha256      TEXT NOT NULL,
                            created_at  TEXT NOT NULL,
                            updated_at  TEXT NOT NULL
                        )
                        """).update();
    }
}
