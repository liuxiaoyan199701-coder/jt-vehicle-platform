package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.UpgradePackage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class UpgradePackageRepository {

    private final JdbcClient jdbc;

    public UpgradePackageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<UpgradePackage> findAll() {
        return jdbc.sql("""
                        SELECT id, name, version, maker_id, file_name, file_path,
                               size_bytes, sha256, created_at, updated_at
                        FROM upgrade_package ORDER BY id DESC
                        """).query(UpgradePackageRepository::map).list();
    }

    public Optional<UpgradePackage> findById(long id) {
        return jdbc.sql("""
                        SELECT id, name, version, maker_id, file_name, file_path,
                               size_bytes, sha256, created_at, updated_at
                        FROM upgrade_package WHERE id = ?
                        """).param(id).query(UpgradePackageRepository::map).optional();
    }

    public long insert(UpgradePackage value) {
        String now = Timestamps.now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO upgrade_package (
                            name, version, maker_id, file_name, file_path,
                            size_bytes, sha256, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(value.name()).param(value.version()).param(value.makerId())
                .param(value.fileName()).param(value.filePath()).param(value.sizeBytes())
                .param(value.sha256()).param(now).param(now).update(key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("创建升级包后未返回主键");
        return id.longValue();
    }

    public int delete(long id) {
        return jdbc.sql("DELETE FROM upgrade_package WHERE id = ?").param(id).update();
    }

    private static UpgradePackage map(ResultSet rs, int row) throws SQLException {
        return new UpgradePackage(rs.getLong("id"), rs.getString("name"), rs.getString("version"),
                rs.getString("maker_id"), rs.getString("file_name"), rs.getString("file_path"),
                rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("created_at"), rs.getString("updated_at"));
    }
}
