package io.github.jtconsole.domain;

import java.util.List;

/**
 * 角色。{@code tenantId} 为空即平台内置模板，非空即租户自定义角色。
 * 内置角色的权限集合由代码在启动时同步，界面不可修改。
 */
public record Role(
        long id,
        Long tenantId,
        String code,
        String name,
        boolean builtin,
        String dataScope,
        String remark,
        String createdAt,
        String updatedAt) {

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String TENANT_OPERATOR = "TENANT_OPERATOR";
    public static final String TENANT_VIEWER = "TENANT_VIEWER";

    public DataScopeType dataScopeValue() {
        return DataScopeType.of(dataScope);
    }

    public boolean platformRole() {
        return tenantId == null;
    }

    public Summary summary() {
        return new Summary(id, code, name, builtin);
    }

    public record Summary(long id, String code, String name, boolean builtin) {}

    /** 角色详情，附带权限码与自定义部门集合。 */
    public record Details(
            Role role,
            List<String> permissions,
            List<Long> departmentIds,
            int accountCount) {}
}
