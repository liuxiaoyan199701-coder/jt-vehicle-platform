package io.github.jtconsole.repository;

import io.github.jtconsole.domain.RecordingUploadTask;
import io.github.jtconsole.security.DataScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RecordingUploadRepository {
    private static final String COLUMNS = """
            id, tenant_id AS tenantId, device_id AS deviceId,
            command_serial_no AS commandSerialNo, channel_no AS channelNo,
            start_at AS startAt, end_at AS endAt, media_type AS mediaType,
            stream_type AS streamType, storage_type AS storageType,
            condition_bits AS conditionBits, status, result_code AS resultCode,
            credential_expires_at AS credentialExpiresAt, file_name AS fileName,
            file_size AS fileSize, access_address AS accessAddress, content_type AS contentType,
            created_at AS createdAt, updated_at AS updatedAt, completed_at AS completedAt
            """;
    private final JdbcClient jdbc;

    public RecordingUploadRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(RecordingUploadTask task) {
        jdbc.sql("""
                INSERT INTO recording_upload_task
                    (id, tenant_id, device_id, channel_no, start_at, end_at,
                     media_type, stream_type, storage_type, condition_bits, status,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(task.id(), task.tenantId(), task.deviceId(), task.channelNo(),
                        task.startAt(), task.endAt(), task.mediaType(), task.streamType(),
                        task.storageType(), task.conditionBits(), task.status(),
                        task.createdAt(), task.updatedAt()).update();
    }

    public void markDispatched(String taskId, int serialNo, String expiresAt, String updatedAt) {
        jdbc.sql("""
                UPDATE recording_upload_task
                SET command_serial_no = ?, credential_expires_at = ?, status = 'DISPATCHED', updated_at = ?
                WHERE id = ? AND status = 'CREATED'
                """).params(serialNo, expiresAt, updatedAt, taskId).update();
    }

    public void markDispatchFailed(String taskId, String updatedAt) {
        jdbc.sql("""
                UPDATE recording_upload_task SET status = 'FAILED', updated_at = ?
                WHERE id = ? AND status = 'CREATED'
                """).params(updatedAt, taskId).update();
    }

    /** Idempotent state transition keyed by device + original 9206 serial number. */
    public int markTerminalCompleted(
            String deviceId, int commandSerialNo, int resultCode, String updatedAt) {
        String status = resultCode == 0 ? "COMPLETED" : "FAILED";
        return jdbc.sql("""
                UPDATE recording_upload_task
                SET status = ?, result_code = ?, updated_at = ?, completed_at = ?
                WHERE id = (
                    SELECT id FROM recording_upload_task
                    WHERE device_id = ? AND command_serial_no = ?
                    ORDER BY created_at DESC LIMIT 1
                ) AND (result_code IS NULL OR result_code = ?)
                """).params(status, resultCode, updatedAt, updatedAt,
                        deviceId, commandSerialNo, resultCode).update();
    }

    public int attachFile(
            String taskId, String fileName, long size, String accessAddress,
            String contentType, String updatedAt) {
        return jdbc.sql("""
                UPDATE recording_upload_task
                SET file_name = ?, file_size = ?, access_address = ?, content_type = ?,
                    status = CASE WHEN status IN ('COMPLETED', 'FAILED') THEN status ELSE 'FILE_RECEIVED' END,
                    updated_at = ?
                WHERE id = ?
                """).params(fileName, size, accessAddress, contentType, updatedAt, taskId).update();
    }

    public Optional<RecordingUploadTask> findById(String taskId, DataScope scope) {
        if (scope.empty()) return Optional.empty();
        List<Object> params = new ArrayList<>();
        params.add(taskId);
        params.addAll(scope.parameters());
        return jdbc.sql("SELECT " + COLUMNS + " FROM recording_upload_task WHERE id = ?"
                        + scope.deviceCondition("device_id"))
                .params(params).query(RecordingUploadTask.class).optional();
    }

    public List<RecordingUploadTask> findByDevice(String deviceId, int limit, DataScope scope) {
        if (scope.empty()) return List.of();
        List<Object> params = new ArrayList<>();
        params.add(deviceId);
        params.addAll(scope.parameters());
        params.add(limit);
        return jdbc.sql("SELECT " + COLUMNS + " FROM recording_upload_task WHERE device_id = ?"
                        + scope.deviceCondition("device_id")
                        + " ORDER BY created_at DESC LIMIT ?")
                .params(params).query(RecordingUploadTask.class).list();
    }

    public Optional<RecordingUploadTask> findByIdInternal(String taskId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM recording_upload_task WHERE id = ?")
                .param(taskId).query(RecordingUploadTask.class).optional();
    }

    public Optional<RecordingUploadTask> findByCommandInternal(String deviceId, int serialNo) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM recording_upload_task"
                        + " WHERE device_id = ? AND command_serial_no = ?"
                        + " ORDER BY created_at DESC LIMIT 1")
                .params(deviceId, serialNo).query(RecordingUploadTask.class).optional();
    }
}
