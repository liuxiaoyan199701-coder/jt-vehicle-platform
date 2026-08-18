package io.github.jtconsole.migration;

import io.github.jtconsole.config.Timestamps;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v8：把 {@code media_file.captured_at} 里残留的非东八区时间修正回来。
 *
 * <p><b>为什么 v6 之后还会再脏</b>：v6 一次性把全库时间戳归一化到了 +08:00，{@code media_file}
 * 也在覆盖范围内。但投递侧漏了一处——{@code MediaIngestionService} 直接用了网关送来的
 * {@code receivedAt} 原值，没走 {@link Timestamps#normalize}。网关给的是
 * {@code Instant.toString()}，即 {@code 2026-08-18T06:59:31.897793547Z}：UTC、纳秒精度。
 * 于是 v6 之后新入库的每一张抓拍又都是脏的。
 *
 * <p>后果不止是界面差 8 小时。时间列是字符串，比较走字节序：{@code TimeBounds} 把查询边界
 * 归一化成 {@code ...+08:00}，而 {@code 'Z'}（0x5A）与 {@code '+'}（0x2B）根本不在同一位置上，
 * 于是多媒体页按时间段筛选会**静默少查**，告警时段联查永远返回空——都不报错。
 *
 * <p>投递侧的漏归一化已在同一次变更中修掉，本迁移只负责把存量数据拉齐。幂等：已经是
 * {@code +08:00} 的行不会被再次转换。
 */
@Component
public class V8MediaTimestampMigration implements SchemaMigration {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(V8MediaTimestampMigration.class);

    @Override
    public int version() {
        return 8;
    }

    @Override
    public String description() {
        return "修正 media_file.captured_at 中未归一化的时间戳";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        // 只挑出不是 +08:00 结尾的行。用 SQL 过滤而不是全表读出来判断：抓拍表可能很大，
        // 而需要修的通常只是漏归一化那段时间产生的一小部分。
        List<Map<String, Object>> dirty = jdbc.sql("""
                        SELECT id, captured_at
                        FROM media_file
                        WHERE captured_at IS NOT NULL
                          AND captured_at NOT LIKE '%+08:00'
                        """)
                .query()
                .listOfRows();

        if (dirty.isEmpty()) {
            return;
        }

        int fixed = 0;
        for (Map<String, Object> row : dirty) {
            Object raw = row.get("captured_at");
            if (!(raw instanceof String value) || value.isBlank()) {
                continue;
            }
            String normalized = Timestamps.normalize(value);
            if (normalized == null || normalized.equals(value)) {
                // 解析不了就原样留着。凭空改写一个看不懂的时间，比留着它更糟——
                // 至少留着还能看出这行有问题。
                LOGGER.warn("media_file id={} 的时间戳无法归一化，保持原值：{}", row.get("id"), value);
                continue;
            }
            jdbc.sql("UPDATE media_file SET captured_at = ? WHERE id = ?")
                    .param(normalized)
                    .param(row.get("id"))
                    .update();
            fixed++;
        }
        LOGGER.info("归一化了 {} 条抓拍时间戳（共发现 {} 条非 +08:00）", fixed, dirty.size());
    }
}
