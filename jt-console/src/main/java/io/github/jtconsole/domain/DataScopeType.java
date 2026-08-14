package io.github.jtconsole.domain;

import java.util.Locale;

/**
 * 角色的数据范围。租户边界是硬边界，不受此枚举影响；这里只决定租户内部还能再收窄到哪些部门。
 */
public enum DataScopeType {
    /** 本租户全部车辆，含未分配部门的车辆。 */
    TENANT,
    /** 账号所属部门及其全部子孙部门。 */
    DEPT_AND_CHILDREN,
    /** 仅账号所属部门。 */
    DEPT,
    /** 角色显式指定的部门集合（{@code role_dept}）。 */
    CUSTOM;

    public static DataScopeType of(String value) {
        if (value == null || value.isBlank()) {
            return DEPT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            // 未知取值按最窄范围处理：解析失败绝不能放宽可见性。
            return DEPT;
        }
    }
}
