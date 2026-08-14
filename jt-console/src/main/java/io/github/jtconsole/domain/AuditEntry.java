package io.github.jtconsole.domain;

/**
 * 一条审计记录。{@code detail} 已在采集侧脱敏，绝不含密码、token 与密钥。
 */
public record AuditEntry(
        long id,
        String occurredAt,
        Long tenantId,
        Long accountId,
        String username,
        String action,
        String resourceType,
        String resourceId,
        String method,
        String path,
        String detail,
        String sourceIp,
        String result,
        Integer statusCode,
        Integer durationMs) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
    public static final String DENIED = "DENIED";
}
