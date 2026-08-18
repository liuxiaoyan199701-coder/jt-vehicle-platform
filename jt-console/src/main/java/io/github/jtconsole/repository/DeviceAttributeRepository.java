package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 设备协议版本。车辆控制 0x8500 等双版本报文在 2011 与 2019 的编码不同，
 * 下发前必须知道设备实际注册的版本，因此从每条投递信封里持续记录。
 */
@Repository
public class DeviceAttributeRepository {

    private final JdbcClient jdbc;

    public DeviceAttributeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertProtocolVersion(String deviceId, String protocolVersion) {
        if (protocolVersion == null || protocolVersion.isBlank()) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO device_attribute (device_id, protocol_version, updated_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT(device_id) DO UPDATE SET
                            protocol_version = excluded.protocol_version,
                            updated_at = excluded.updated_at
                        """)
                .param(deviceId)
                .param(protocolVersion)
                .param(Timestamps.now())
                .update();
    }

    /**
     * @return 形如 {@code "JT/T 808-2019/1"} 或 {@code "JT/T 808-2013"} 的版本串
     */
    public Optional<String> findProtocolVersion(String deviceId) {
        return jdbc.sql("SELECT protocol_version FROM device_attribute WHERE device_id = ?")
                .param(deviceId)
                .query(String.class)
                .optional();
    }
}
