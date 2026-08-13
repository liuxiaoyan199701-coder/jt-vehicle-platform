package io.github.jtconsole.repository;

import io.github.jtconsole.domain.MediaFile;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MediaRepository {

    private final JdbcClient jdbc;

    public MediaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等插入：同一 (device_id, file_id) 的重复投递不产生新行，也不报错。
     */
    public void insertIgnore(MediaFile file) {
        jdbc.sql("""
                        INSERT OR IGNORE INTO media_file
                            (device_id, file_id, file_type, file_format, file_name, size,
                             access_address, channel_id, event_code, captured_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(file.deviceId())
                .param(file.fileId())
                .param(file.fileType())
                .param(file.fileFormat())
                .param(file.fileName())
                .param(file.size())
                .param(file.accessAddress())
                .param(file.channelId())
                .param(file.eventCode())
                .param(file.capturedAt())
                .update();
    }

    /**
     * 某设备最近上传的多媒体文件，最新的在前。
     */
    public List<MediaFile> findRecentByDevice(String deviceId, int limit) {
        return jdbc.sql("""
                        SELECT id, device_id AS deviceId, file_id AS fileId, file_type AS fileType,
                               file_format AS fileFormat, file_name AS fileName, size,
                               access_address AS accessAddress, channel_id AS channelId,
                               event_code AS eventCode, captured_at AS capturedAt
                        FROM media_file
                        WHERE device_id = ?
                        ORDER BY captured_at DESC, id DESC
                        LIMIT ?
                        """)
                .param(deviceId)
                .param(limit)
                .query(MediaFile.class)
                .list();
    }
}
