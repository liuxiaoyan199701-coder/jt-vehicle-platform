package io.github.jtconsole.repository;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 结果集取值助手。
 *
 * <p>{@code ResultSet.wasNull()} 只反映最近一次取值，写进记录构造器的实参列表里会因为
 * 求值顺序而指向错误的列，因此可空整型统一走这里。
 */
final class RowValues {

    private RowValues() {
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static boolean flag(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) == 1;
    }
}
