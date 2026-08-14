package io.github.jtconsole.domain;

/**
 * 续费台账。刻意没有 {@code updatedAt}：记录一旦录入不可修改与删除，
 * 录错以红冲（负额度、负金额）新增一条纠正。
 */
public record TenantOrder(
        long id,
        long tenantId,
        Long planId,
        String planName,
        int months,
        long amountCents,
        String previousExpiresAt,
        String newExpiresAt,
        String operator,
        String remark,
        String createdAt) {
}
