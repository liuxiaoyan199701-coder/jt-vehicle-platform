package io.github.jtconsole.domain;

/**
 * 登录账号。{@code tenantId} 为空即平台账号；密码只以 BCrypt 哈希保存。
 *
 * <p>{@code passwordHash} 绝不参与任何对外序列化，控制器一律返回 {@link AccountView}。
 */
public record Account(
        long id,
        String username,
        String passwordHash,
        String displayName,
        Long tenantId,
        Long departmentId,
        Long positionId,
        String status,
        String lastLoginAt,
        String createdAt,
        String updatedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";

    public boolean enabled() {
        return ACTIVE.equalsIgnoreCase(status);
    }

    public boolean platformAccount() {
        return tenantId == null;
    }
}
