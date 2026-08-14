package io.github.jtconsole.domain;

/**
 * 岗位。纯人事标签，MUST NOT 参与任何权限或数据范围判定——
 * 一旦岗位也能授权，它就变成了第二套角色模型。
 */
public record Position(
        long id,
        long tenantId,
        String name,
        int sortOrder,
        String remark,
        String createdAt,
        String updatedAt) {
}
