package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;

/**
 * 时间范围查询的边界规范化。
 *
 * <p><b>存在的理由</b>：时间列存的是字符串，比较走 SQLite 的字节序而不是日期语义。入库格式用
 * {@code T} 分隔（{@code 2026-08-17T05:16:55}），而调用方——尤其是 AI 工具背后的模型——最自然的
 * 写法是空格分隔。偏偏 {@code 'T'} 是 0x54、空格是 0x20，于是
 * {@code "…T05:16:55" <= "… 23:59:59"} 为**假**：同一天的记录一条都查不出来，缩小范围没用，
 * 放宽到全天也没用，而且不报任何错。
 *
 * <p>这类约束靠在参数说明里写清楚是守不住的：它同时约束着网页、AI 工具两条调用路径，
 * 而其中一条的参数是模型现场生成的。规范化必须放在依赖该格式的 SQL 这一侧。
 */
public final class TimeBounds {

    private TimeBounds() {
    }

    /** 下界：只给日期时取当天零点。 */
    public static String lower(String bound) {
        return normalize(bound, "T00:00:00.000");
    }

    /** 上界：只给日期时取当天最后一毫秒。 */
    public static String upper(String bound) {
        return normalize(bound, "T23:59:59.999");
    }

    /**
     * 规范到与入库一致的口径：{@code yyyy-MM-ddTHH:mm:ss.SSS+08:00}。
     *
     * <p>边界必须补齐到毫秒并带上偏移，否则字典序会在两个地方出错：缺小数位时
     * {@code "…T09:40:00"} 会小于同一秒的 {@code "…T09:40:00.123+08:00"}，缺偏移时
     * {@code '+'}（0x2B）又排在数字之前。两种情况都会悄悄漏掉边界附近的记录。
     */
    /** 把外部时间参数按下界口径归一后转换为绝对时刻，供非 SQL 的时段查询复用。 */
    public static java.time.Instant instant(String bound) {
        return parseInstant(lower(bound), bound);
    }

    /** 把外部时间参数按上界口径归一；仅给日期时取当天最后一毫秒。 */
    public static java.time.Instant upperInstant(String bound) {
        return parseInstant(upper(bound), bound);
    }

    private static java.time.Instant parseInstant(String normalized, String original) {
        if (normalized == null) {
            throw new IllegalArgumentException("时间不能为空");
        }
        try {
            return java.time.OffsetDateTime.parse(normalized).toInstant();
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new IllegalArgumentException("时间格式不正确：" + original, invalid);
        }
    }

    private static String normalize(String bound, String bareDateSuffix) {
        if (bound == null || bound.isBlank()) {
            return null;
        }
        String trimmed = bound.trim().replace(' ', 'T');
        // 「查 8 月 17 号」给过来的就是一个光秃秃的日期，补齐成当天的整段区间。
        if (trimmed.length() == 10) {
            return trimmed + bareDateSuffix + "+08:00";
        }
        return Timestamps.normalize(padToMillis(trimmed));
    }

    /** 补齐到毫秒。已带时区后缀的交给 {@link Timestamps#normalize} 换算，不在这里动。 */
    private static String padToMillis(String value) {
        int timeStart = value.indexOf('T');
        if (timeStart < 0) {
            return value;
        }
        String time = value.substring(timeStart);
        boolean zoned = time.endsWith("Z") || time.indexOf('+') > 0 || time.lastIndexOf('-') > 0;
        if (zoned || time.indexOf('.') > 0) {
            return value;
        }
        // 形如 T09:40:00 或 T09:40
        return time.length() == 6 ? value + ":00.000" : value + ".000";
    }
}
