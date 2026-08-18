package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Position;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class PositionRepository {

    /**
     * 用文本块而不是普通字面量：拼接方与它相接的下一段常以 {@code FROM} 开头且不带前导空格，
     * 靠文本块末尾的换行分隔。写成单行字面量会拼出 {@code updated_atFROM position}。
     */
    private static final String COLUMNS = """
            id, tenant_id, name, sort_order, remark, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public PositionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Position> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM position WHERE id = ?")
                .param(id).query(Position.class).optional();
    }

    public List<Position> findByTenant(long tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM position WHERE tenant_id = ? ORDER BY sort_order, id
                        """)
                .param(tenantId).query(Position.class).list();
    }

    public boolean nameExists(long tenantId, String name, Long excludedId) {
        String sql = excludedId == null
                ? "SELECT COUNT(*) FROM position WHERE tenant_id = ? AND name = ?"
                : "SELECT COUNT(*) FROM position WHERE tenant_id = ? AND name = ? AND id <> ?";
        var spec = jdbc.sql(sql).param(tenantId).param(name);
        if (excludedId != null) {
            spec = spec.param(excludedId);
        }
        Integer count = spec.query(Integer.class).single();
        return count != null && count > 0;
    }

    public long insert(Position position) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO position (tenant_id, name, sort_order, remark,
                                              created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .param(position.tenantId()).param(position.name()).param(position.sortOrder())
                .param(position.remark()).param(position.createdAt()).param(position.updatedAt())
                .update(key);
        Number value = key.getKey();
        if (value == null) {
            throw new IllegalStateException("创建岗位后未返回主键");
        }
        return value.longValue();
    }

    public int update(long id, String name, int sortOrder, String remark) {
        return jdbc.sql("""
                        UPDATE position SET name = ?, sort_order = ?, remark = ?, updated_at = ?
                        WHERE id = ?
                        """)
                .param(name).param(sortOrder).param(remark)
                .param(Timestamps.now()).param(id)
                .update();
    }

    public int delete(long id) {
        return jdbc.sql("DELETE FROM position WHERE id = ?").param(id).update();
    }

    public void deleteByTenant(long tenantId) {
        jdbc.sql("DELETE FROM position WHERE tenant_id = ?").param(tenantId).update();
    }
}
