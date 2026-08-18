package io.github.jtconsole.config;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 全平台时间戳的唯一书写口径：**东八区本地时间，带显式 {@code +08:00} 偏移**。
 *
 * <p>例：{@code 2026-08-17T17:41:15.123+08:00}
 *
 * <p><b>为什么是「带偏移的本地时间」而不是 UTC、也不是裸本地时间</b>——三个约束同时成立才行：
 *
 * <ul>
 *   <li><b>必须能被 {@link Instant#parse} 解析。</b>租户到期判定等处直接 {@code Instant.parse}
 *       存储值，而裸本地时间（{@code 2026-08-17T17:41:15}）会让它抛
 *       {@code DateTimeParseException}——这条路走不通。</li>
 *   <li><b>必须能被 SQLite 的 {@code julianday()} 正确归一。</b>已实测
 *       {@code julianday('…T17:41:15+08:00')} 与 {@code julianday('…T09:41:15Z')} 完全相等，
 *       所以设备时间与接收时间之间的比较不需要再补时差。</li>
 *   <li><b>必须让字典序等于时间序。</b>时间列是 TEXT，范围查询走的是字节比较。只要全库偏移一致，
 *       字典序就与时间序一致；一旦混入 {@code Z} 结尾的旧值就会错乱——{@code 'Z'} 是 0x5A、
 *       {@code '+'} 是 0x2B，两种格式互相比较的结果毫无意义。<b>因此存量转换必须与代码切换同批
 *       上线，不能分两次做。</b></li>
 * </ul>
 *
 * <p>平台面向国内营运车辆，终端上报的本来就是北京时间，且中国不实行夏令时——偏移恒为 +08:00，
 * 不存在跨夏令时的歧义。
 */
public final class Timestamps {

    /** 东八区。中国无夏令时，该偏移恒定。 */
    public static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    /**
     * 毫秒精度。刻意固定精度而不用 {@link Instant#toString()} 的可变精度：后者在整秒时省略小数位，
     * 得到的字符串长度不一，而长度不一会让字典序在「秒相同、一个带小数一个不带」时给出错误结论。
     */
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");

    private Timestamps() {
    }

    /** 当前时刻。取代散落各处的 {@code Instant.now().toString()}。 */
    public static String now() {
        return of(Instant.now());
    }

    public static String of(Instant instant) {
        return FORMAT.format(instant.atOffset(ZONE));
    }

    /**
     * 把终端上报的**无时区本地时间**补上偏移。
     *
     * <p>JT/T 808 的时间字段是 BCD 的 {@code YYMMDDHHMMSS}，协议层面不带时区，按标准即终端本地
     * 时间。国内终端报的就是北京时间，所以这里补 {@code +08:00} 是把一直以来的隐含约定写明确，
     * 而不是改变语义。
     *
     * @return 补齐偏移后的时间戳；入参为空或无法识别时原样返回，交由上层按「时间不可用」处理
     */
    public static String ofDeviceLocal(String bareLocal) {
        if (bareLocal == null || bareLocal.isBlank()) {
            return bareLocal;
        }
        return normalize(bareLocal);
    }

    /**
     * 把任意可识别的时间戳换算成本口径。用于迁移存量数据与规范化外部输入。
     *
     * @return 换算结果；无法识别时原样返回，宁可留下一个看得见的异常值，也不要悄悄改成「现在」
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim().replace(' ', 'T');
        if (!hasZoneSuffix(trimmed)) {
            // 走一遍解析再输出，而不是直接拼接后缀：拼接会留下「有的带毫秒、有的不带」的长度差，
            // 而 '+'(0x2B) 排在 '.'(0x2E) 之前，长度不一会让范围查询在边界上悄悄漏掉记录。
            return toLocalDateTime(trimmed)
                    .map(local -> FORMAT.format(local.atOffset(ZONE)))
                    .orElse(trimmed);
        }
        try {
            return of(Instant.parse(trimmed));
        } catch (DateTimeParseException notAnInstant) {
            return trimmed;
        }
    }

    /**
     * 解析成不带时区的本地时间，用于「同一天」「谁更晚」这类只关心墙上时间的判断。
     *
     * <p><b>两种写法都要认</b>：带偏移的（现行口径）与无偏移的（历史数据、外部来源、以及直接调用
     * 服务层的测试）。只认一种是不够的——{@link LocalDateTime#parse} 遇到带偏移的值会抛异常，
     * 而调用方普遍把解析失败当成「时间不可用」静默降级：日统计会因此判定乱序而不累加里程，
     * 业务日会因此回落到接收时间。两者都不报错，只是数字悄悄变得不对。
     *
     * @return 解析结果；无法识别时为空，由调用方决定如何降级
     */
    public static Optional<LocalDateTime> toLocalDateTime(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim().replace(' ', 'T');
        try {
            return Optional.of(OffsetDateTime.parse(trimmed).toLocalDateTime());
        } catch (DateTimeParseException notOffset) {
            try {
                return Optional.of(LocalDateTime.parse(trimmed));
            } catch (DateTimeParseException notLocal) {
                return Optional.empty();
            }
        }
    }

    private static boolean hasZoneSuffix(String value) {
        if (value.endsWith("Z")) {
            return true;
        }
        // 只看时间部分的尾巴，避免把日期里的 '-' 当成负偏移。
        int timeStart = value.indexOf('T');
        if (timeStart < 0) {
            return false;
        }
        String time = value.substring(timeStart);
        return time.indexOf('+') > 0 || time.lastIndexOf('-') > 0;
    }
}
