package io.github.jtconsole.security;

import io.github.jtconsole.domain.Role;
import java.util.List;
import java.util.Set;

/**
 * 一次请求解析出的授权上下文：身份 + 权限码集合 + 数据范围。
 *
 * <p>控制器通过参数注入拿到它，并把 {@link #scope()} 显式传给服务与仓储。
 * 授权判定一律以此为准，MUST NOT 采信请求里的角色、权限或租户参数。
 */
public record AuthorizedPrincipal(
        long accountId,
        String username,
        String displayName,
        Long tenantId,
        String tenantName,
        boolean platform,
        Set<String> permissions,
        List<Role.Summary> roles,
        DataScope scope) {

    public AuthorizedPrincipal {
        permissions = Set.copyOf(permissions);
        roles = List.copyOf(roles);
    }

    public boolean hasPermission(String code) {
        return permissions.contains(code);
    }

    public boolean hasAnyPermission(String... codes) {
        for (String code : codes) {
            if (permissions.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /** 一个写权限都没有的账号：安全层据此集中拒绝其全部非 GET 请求。 */
    public boolean readOnly() {
        return Permissions.readOnly(permissions);
    }

    /**
     * 归属写入使用的租户。平台账号需由调用方显式指定目标租户，因此这里返回空。
     */
    public Long owningTenantId() {
        return platform ? null : tenantId;
    }
}
