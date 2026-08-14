package io.github.jtconsole.domain;

/** 自助注册申请。审批通过前对应的租户为 {@code PENDING_APPROVAL}、账号为禁用状态。 */
public record TenantRegistration(
        long id,
        long tenantId,
        long accountId,
        String companyName,
        String contactName,
        String contactPhone,
        String username,
        String status,
        String reviewedBy,
        String reviewedAt,
        String reviewNote,
        String sourceIp,
        String createdAt,
        String updatedAt) {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
}
