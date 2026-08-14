package io.github.jtconsole.domain;

/**
 * 权限点定义。由代码声明、启动时同步进 {@code permission} 表，界面只能展示与勾选，不能新建。
 *
 * @param code         权限码，形如 {@code vehicle:create}
 * @param module       所属模块，界面按此分组
 * @param name         中文名称
 * @param platformOnly 是否平台级权限；租户自定义角色不得包含
 * @param write        是否写权限；一个写权限都没有的账号会被安全层直接拒绝所有非 GET 请求
 * @param sortOrder    模块内展示顺序
 */
public record PermissionDefinition(
        String code,
        String module,
        String name,
        boolean platformOnly,
        boolean write,
        int sortOrder) {
}
