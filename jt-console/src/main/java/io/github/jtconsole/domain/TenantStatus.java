package io.github.jtconsole.domain;

/** 租户生命周期状态。到期不是独立状态，由 {@code expires_at} 与当前时刻实时比较得出。 */
public enum TenantStatus {
    /** 正常可用。 */
    ACTIVE,
    /** 平台管理员手工停用。 */
    SUSPENDED,
    /** 自助注册后等待平台审批。 */
    PENDING_APPROVAL,
    /** 注册申请被拒绝。 */
    REJECTED;

    public static TenantStatus of(String value) {
        if (value == null || value.isBlank()) {
            return SUSPENDED;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return SUSPENDED;
        }
    }
}
