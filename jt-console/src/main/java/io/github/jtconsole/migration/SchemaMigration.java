package io.github.jtconsole.migration;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 一次数据库结构或种子数据的增量演进。
 *
 * <p>{@code schema.sql} 只能用 {@code CREATE TABLE IF NOT EXISTS} 建新表，无法给已存在的表补列，
 * 因此历史上只能靠「另建一张影子表」绕开（见 {@code device_attribute}）。租户化要给
 * {@code vehicle}/{@code fleet}/{@code geofence} 补列，影子表的做法会让 join 数量失控，
 * 故引入按 {@code PRAGMA user_version} 编号的增量迁移。
 */
public interface SchemaMigration {

    /** 迁移版本号，从 1 开始且全局唯一；执行成功后写入 {@code PRAGMA user_version}。 */
    int version();

    /** 供启动日志定位失败步骤的简述。 */
    String description();

    /** 迁移语句。由 runner 包在单个事务里执行，抛异常即整体回滚且不推进版本号。 */
    void apply(JdbcClient jdbc);
}
