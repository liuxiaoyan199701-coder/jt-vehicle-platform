package io.github.jtconsole.domain;

import java.time.Instant;

/**
 * 租户档案。{@code expiresAt} 为空表示永不过期——存量部署升级后归入的默认租户即取该语义，
 * 避免升级当天突然全员到期。
 */
public record Tenant(
        long id,
        String code,
        String name,
        String status,
        Long planId,
        String expiresAt,
        String contactName,
        String contactPhone,
        String remark,
        String createdAt,
        String updatedAt) {

    /** 全局默认配置借用的租户标识，不对应任何真实租户。 */
    public static final long GLOBAL_CONFIG_SCOPE = 0L;

    public TenantStatus statusValue() {
        return TenantStatus.of(status);
    }

    public boolean expired(Instant now) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return false;
        }
        try {
            return !now.isBefore(Instant.parse(expiresAt));
        } catch (RuntimeException unparsable) {
            // 时间列损坏时按未过期处理：错误的到期判定会让整个租户断服，代价远高于放行。
            return false;
        }
    }

    /** 租户当前是否可用：状态为 ACTIVE 且未到期。 */
    public boolean active(Instant now) {
        return statusValue() == TenantStatus.ACTIVE && !expired(now);
    }
}
