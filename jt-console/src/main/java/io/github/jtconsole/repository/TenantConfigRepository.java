package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 租户配置覆盖值。{@code tenant_id = 0}（{@code Tenant.GLOBAL_CONFIG_SCOPE}）保留给全局默认值，
 * 与「租户覆盖 → 全局默认」的两级解析一一对应。
 */
@Repository
public class TenantConfigRepository {

    private final JdbcClient jdbc;

    public TenantConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> findByScope(long scopeId) {
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.sql("SELECT config_key, config_value FROM tenant_config WHERE tenant_id = ?")
                .param(scopeId)
                .query((rs, rowNum) -> {
                    values.put(rs.getString("config_key"), rs.getString("config_value"));
                    return null;
                })
                .list();
        return values;
    }

    public Optional<String> find(long scopeId, String key) {
        return jdbc.sql("""
                        SELECT config_value FROM tenant_config
                        WHERE tenant_id = ? AND config_key = ?
                        """)
                .param(scopeId).param(key)
                .query(String.class)
                .optional();
    }

    public void upsert(long scopeId, String key, String value) {
        jdbc.sql("""
                        INSERT INTO tenant_config (tenant_id, config_key, config_value, updated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(tenant_id, config_key) DO UPDATE SET
                            config_value = excluded.config_value,
                            updated_at = excluded.updated_at
                        """)
                .param(scopeId).param(key).param(value).param(Timestamps.now())
                .update();
    }

    public int delete(long scopeId, String key) {
        return jdbc.sql("DELETE FROM tenant_config WHERE tenant_id = ? AND config_key = ?")
                .param(scopeId).param(key).update();
    }

    public void deleteByScope(long scopeId) {
        jdbc.sql("DELETE FROM tenant_config WHERE tenant_id = ?").param(scopeId).update();
    }
}
