package io.github.jtconsole.security;

import io.github.jtconsole.domain.Role;
import io.github.jtconsole.migration.SchemaMigrationRunner;
import io.github.jtconsole.repository.PermissionRepository;
import io.github.jtconsole.repository.RoleRepository;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动期把代码里的权限码目录与内置角色同步进数据库。
 *
 * <p>刻意不放进迁移：迁移只跑一次，而权限码与内置角色的权限集合会随版本演进——
 * 新版本新增的权限码若不回填，平台管理员会永远缺少对新功能的授权。
 */
@Component
public class PermissionCatalogSynchronizer implements InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(PermissionCatalogSynchronizer.class);

    private final PermissionRepository permissions;
    private final RoleRepository roles;

    public PermissionCatalogSynchronizer(
            PermissionRepository permissions,
            RoleRepository roles,
            SchemaMigrationRunner migrations) {
        this.permissions = permissions;
        this.roles = roles;
        // 仅用于强制 bean 创建顺序：表结构必须先于目录同步就绪。
        migrations.currentVersion();
    }

    @Override
    @Transactional
    public void afterPropertiesSet() {
        permissions.synchronize(Permissions.catalog());
        int removed = permissions.deleteInactiveRoleGrants();
        if (removed > 0) {
            LOGGER.info("清理了 {} 条引用已删除权限码的角色配置", removed);
        }

        syncBuiltinRole(Role.PLATFORM_ADMIN, "平台管理员",
                "跨租户管理平台、租户、套餐与全部业务数据", Permissions.PLATFORM_ADMIN_PERMISSIONS);
        syncBuiltinRole(Role.TENANT_ADMIN, "租户管理员",
                "管理本租户的用户、角色、组织与全部业务", Permissions.TENANT_ADMIN_PERMISSIONS);
        syncBuiltinRole(Role.TENANT_OPERATOR, "租户操作员",
                "本租户全部业务操作，不含系统管理", Permissions.TENANT_OPERATOR_PERMISSIONS);
        syncBuiltinRole(Role.TENANT_VIEWER, "租户只读",
                "仅查看本租户数据，不能执行任何写操作", Permissions.TENANT_VIEWER_PERMISSIONS);
        LOGGER.info("权限目录与内置角色已同步（权限点 {} 个）", Permissions.catalog().size());
    }

    private void syncBuiltinRole(String code, String name, String remark, Set<String> grants) {
        String now = Instant.now().toString();
        long roleId = roles.findBuiltin(code)
                .map(Role::id)
                .orElseGet(() -> roles.insert(new Role(
                        0L, null, code, name, true, "TENANT", remark, now, now)));
        roles.replacePermissions(roleId, grants);
    }
}
