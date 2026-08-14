package io.github.jtconsole.domain;

/**
 * 租户内单层车队档案。
 *
 * <p>车队是运营分组，不承担数据权限——数据可见范围由部门决定。
 * 把权限挂到车队上会让每次日常调拨都变成一次权限变更事故。
 */
public record Fleet(
        long id,
        String code,
        String name,
        String manager,
        String contactPhone,
        String remark,
        Long tenantId,
        String createdAt,
        String updatedAt) {

    public Summary summary() {
        return new Summary(id, code, name);
    }

    public record Summary(long id, String code, String name) {}
}
