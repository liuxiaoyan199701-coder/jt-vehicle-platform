package io.github.jtconsole.domain;

import java.util.List;

/** 司机档案。身份证号默认脱敏，仅持 driver:manage 权限可见全量。 */
public record Driver(
        Long id,
        String name,
        String idCard,
        String licenseNo,
        String institution,
        String licenseValidPeriod,
        String phone,
        String remark,
        Long departmentId,
        Long tenantId,
        String createdAt,
        String updatedAt) {

    /** 默认脱敏：仅保留前 3 位与后 4 位，其余以星号遮蔽。 */
    public String maskedIdCard() {
        return mask(idCard);
    }

    /** 脱敏规则：保留前 3 位与后 4 位，中段以 {@code *} 遮蔽；过短或空则原样返回。 */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 7) {
            return trimmed;
        }
        return trimmed.substring(0, 3)
                + "*".repeat(trimmed.length() - 7)
                + trimmed.substring(trimmed.length() - 4);
    }
}
