package io.github.jtconsole.domain;

import java.util.List;

/** 账号的对外读模型。刻意不含 {@code passwordHash}，从类型上杜绝密码哈希出现在响应里。 */
public record AccountView(
        long id,
        String username,
        String displayName,
        Long tenantId,
        String tenantName,
        Long departmentId,
        String departmentName,
        Long positionId,
        String positionName,
        String status,
        String lastLoginAt,
        String createdAt,
        String updatedAt,
        List<Role.Summary> roles) {
}
