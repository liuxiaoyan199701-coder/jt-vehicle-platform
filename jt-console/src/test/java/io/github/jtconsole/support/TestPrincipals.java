package io.github.jtconsole.support;

import io.github.jtconsole.domain.Role;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import java.util.List;
import java.util.Set;

/** 测试用的授权上下文构造器。 */
public final class TestPrincipals {

    private TestPrincipals() {
    }

    /** 跨租户、全权限的平台管理员。 */
    public static AuthorizedPrincipal platform() {
        return new AuthorizedPrincipal(
                1L, "platform-admin", "平台管理员", null, null, true,
                Permissions.PLATFORM_ADMIN_PERMISSIONS,
                List.of(new Role.Summary(1L, Role.PLATFORM_ADMIN, "平台管理员", true)),
                DataScope.platform());
    }

    /** 指定租户的租户管理员，数据范围为本租户全部。 */
    public static AuthorizedPrincipal tenantAdmin(long accountId, long tenantId) {
        return new AuthorizedPrincipal(
                accountId, "tenant-" + tenantId, "租户管理员", tenantId, "租户" + tenantId, false,
                Permissions.TENANT_ADMIN_PERMISSIONS,
                List.of(new Role.Summary(2L, Role.TENANT_ADMIN, "租户管理员", true)),
                DataScope.tenantWide(tenantId));
    }

    /** 限定部门集合的租户操作员。 */
    public static AuthorizedPrincipal departmentOperator(
            long accountId, long tenantId, Set<Long> departmentIds) {
        return new AuthorizedPrincipal(
                accountId, "operator-" + accountId, "部门操作员", tenantId, "租户" + tenantId, false,
                Permissions.TENANT_OPERATOR_PERMISSIONS,
                List.of(new Role.Summary(3L, Role.TENANT_OPERATOR, "租户操作员", true)),
                DataScope.departments(tenantId, departmentIds));
    }

    /** 只读账号：没有任何写权限，安全层会据此集中拒绝其非 GET 请求。 */
    public static AuthorizedPrincipal viewer(long accountId, long tenantId) {
        return new AuthorizedPrincipal(
                accountId, "viewer-" + accountId, "只读用户", tenantId, "租户" + tenantId, false,
                Permissions.TENANT_VIEWER_PERMISSIONS,
                List.of(new Role.Summary(4L, Role.TENANT_VIEWER, "租户只读", true)),
                DataScope.tenantWide(tenantId));
    }
}
