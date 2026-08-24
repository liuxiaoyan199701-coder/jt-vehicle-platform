package io.github.jtconsole.migration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * v19：终端台账。记录每台连接过网关的终端及其自报的身份信息。
 *
 * <p><b>与 {@code vehicle} 同键但不合并</b>：档案是人工确认的归属，台账是设备自报的事实。
 * 自报车牌可以乱填，合进档案后就分不清「运维确认过的车牌」与「终端自己说的车牌」，
 * 而这个区分正是建档时最需要的。建档状态也刻意不落列——冗余一个布尔值就得在建档、
 * 改档、删档三处同步维护，漏一处便长期不一致，查询时与 {@code vehicle} 左连接即可得出。
 *
 * <p><b>主键是终端手机号，不是终端 ID</b>。终端 ID 是 0x0100 正文里终端自报的编号
 * （如 {@code 1380000}），平台没有任何一张表按它建键；手机号（如 {@code 138000000000}）
 * 才是全平台主键。terminal_id 只做附加列并加索引，供「按终端自报编号找设备」这一种排查用。
 */
@Component
public class V19TerminalRegistryMigration implements SchemaMigration {

    @Override
    public int version() {
        return 19;
    }

    @Override
    public String description() {
        return "建 terminal 终端台账表";
    }

    @Override
    public void apply(JdbcClient jdbc) {
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS terminal (
                    device_id        TEXT PRIMARY KEY,
                    terminal_id      TEXT,
                    maker_id         TEXT,
                    device_model     TEXT,
                    province_id      INTEGER,
                    city_id          INTEGER,
                    reported_plate   TEXT,
                    reported_color   INTEGER,
                    protocol_version TEXT,
                    first_seen_at    TEXT NOT NULL,
                    last_seen_at     TEXT NOT NULL,
                    last_result      TEXT,
                    updated_at       TEXT NOT NULL
                )
                """).update();
        // 清单默认按「最近见到」倒序翻页。
        jdbc.sql("CREATE INDEX IF NOT EXISTS idx_terminal_last_seen ON terminal (last_seen_at DESC)")
                .update();
        // 「这个终端编号是哪台设备」是排查换机、串号时唯一的入口。
        jdbc.sql("CREATE INDEX IF NOT EXISTS idx_terminal_terminal_id ON terminal (terminal_id)")
                .update();
    }
}
