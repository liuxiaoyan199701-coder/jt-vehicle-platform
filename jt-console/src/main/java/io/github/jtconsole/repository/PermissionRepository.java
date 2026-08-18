package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.PermissionDefinition;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PermissionRepository {

    private final JdbcClient jdbc;

    public PermissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 用代码目录覆盖数据库目录：目录内的权限 UPSERT 并置为生效，
     * 代码中已删除的权限置为失效但保留行，历史角色配置因此仍可追溯。
     */
    @Transactional
    public void synchronize(List<PermissionDefinition> definitions) {
        String now = Timestamps.now();
        jdbc.sql("UPDATE permission SET active = 0, updated_at = ? WHERE active = 1")
                .param(now).update();
        for (PermissionDefinition definition : definitions) {
            jdbc.sql("""
                            INSERT INTO permission (code, module, name, platform_only, sort_order,
                                                    active, updated_at)
                            VALUES (?, ?, ?, ?, ?, 1, ?)
                            ON CONFLICT(code) DO UPDATE SET
                                module = excluded.module,
                                name = excluded.name,
                                platform_only = excluded.platform_only,
                                sort_order = excluded.sort_order,
                                active = 1,
                                updated_at = excluded.updated_at
                            """)
                    .param(definition.code())
                    .param(definition.module())
                    .param(definition.name())
                    .param(definition.platformOnly() ? 1 : 0)
                    .param(definition.sortOrder())
                    .param(now)
                    .update();
        }
    }

    /**
     * 当前生效的权限码。展示用目录直接取自代码（{@code Permissions.catalog()}），
     * 这里只用于清理引用了已删除权限码的角色配置。
     */
    public List<String> findActiveCodes() {
        return jdbc.sql("SELECT code FROM permission WHERE active = 1 ORDER BY code")
                .query(String.class)
                .list();
    }

    /** 删除引用了失效权限码的角色配置，避免它们在授权解析时被误算入并集。 */
    public int deleteInactiveRoleGrants() {
        return jdbc.sql("""
                        DELETE FROM role_permission
                        WHERE permission_code NOT IN (SELECT code FROM permission WHERE active = 1)
                        """)
                .update();
    }
}
