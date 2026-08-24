package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Terminal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TerminalRepository {

    private static final String COLUMNS = """
            device_id AS deviceId, terminal_id AS terminalId, maker_id AS makerId,
            device_model AS deviceModel, province_id AS provinceId, city_id AS cityId,
            reported_plate AS reportedPlate, reported_color AS reportedColor,
            protocol_version AS protocolVersion, first_seen_at AS firstSeenAt,
            last_seen_at AS lastSeenAt, last_result AS lastResult, updated_at AS updatedAt
            """;

    private final JdbcClient jdbc;

    public TerminalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 落台账。同一台终端反复注册只会更新这一行。
     *
     * <p>三条更新规则各有理由，改动前先想清楚会漏掉什么：
     *
     * <ul>
     *   <li>{@code first_seen_at} <b>永不覆盖</b>——它回答「这台终端第一次出现是什么时候」，
     *       被后来的连接改写就永久丢失了。</li>
     *   <li>{@code last_seen_at} <b>取较晚者</b>。投递会乱序（重投、多实例），
     *       无条件覆盖会让一条迟到的旧事件把时间写回过去，看起来像设备突然"退回"了。
     *       与 {@code StatusRepository.touch} 同款条件。</li>
     *   <li>自报字段 <b>非空才覆盖</b>。0x0102 鉴权信封的 payload 里没有终端型号、
     *       制造商这些字段，一律覆盖会把注册时好不容易拿到的信息抹成空。</li>
     * </ul>
     */
    public void upsert(Terminal terminal) {
        jdbc.sql("""
                INSERT INTO terminal
                    (device_id, terminal_id, maker_id, device_model, province_id, city_id,
                     reported_plate, reported_color, protocol_version,
                     first_seen_at, last_seen_at, last_result, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    terminal_id      = COALESCE(excluded.terminal_id, terminal.terminal_id),
                    maker_id         = COALESCE(excluded.maker_id, terminal.maker_id),
                    device_model     = COALESCE(excluded.device_model, terminal.device_model),
                    province_id      = COALESCE(excluded.province_id, terminal.province_id),
                    city_id          = COALESCE(excluded.city_id, terminal.city_id),
                    reported_plate   = COALESCE(excluded.reported_plate, terminal.reported_plate),
                    reported_color   = COALESCE(excluded.reported_color, terminal.reported_color),
                    protocol_version = COALESCE(excluded.protocol_version, terminal.protocol_version),
                    last_seen_at     = MAX(excluded.last_seen_at, terminal.last_seen_at),
                    last_result      = CASE
                        WHEN excluded.last_seen_at >= terminal.last_seen_at
                            THEN COALESCE(excluded.last_result, terminal.last_result)
                        ELSE terminal.last_result END,
                    updated_at       = excluded.updated_at
                """)
                .param(terminal.deviceId()).param(terminal.terminalId())
                .param(terminal.makerId()).param(terminal.deviceModel())
                .param(terminal.provinceId()).param(terminal.cityId())
                .param(terminal.reportedPlate()).param(terminal.reportedColor())
                .param(terminal.protocolVersion())
                .param(terminal.firstSeenAt()).param(terminal.lastSeenAt())
                .param(terminal.lastResult()).param(Timestamps.now())
                .update();
    }

    public Optional<Terminal> findById(String deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM terminal WHERE device_id = ?")
                .param(deviceId).query(TerminalRepository::map).optional();
    }

    static Terminal map(ResultSet rs, int rowNum) throws SQLException {
        return new Terminal(
                rs.getString("deviceId"), rs.getString("terminalId"), rs.getString("makerId"),
                rs.getString("deviceModel"), RowValues.nullableInt(rs, "provinceId"),
                RowValues.nullableInt(rs, "cityId"), rs.getString("reportedPlate"),
                RowValues.nullableInt(rs, "reportedColor"), rs.getString("protocolVersion"),
                rs.getString("firstSeenAt"), rs.getString("lastSeenAt"),
                rs.getString("lastResult"), rs.getString("updatedAt"));
    }
}
