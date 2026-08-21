package io.github.jtconsole.ingest;

import io.github.jtconsole.audit.AuditRecorder;
import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.AuditEntry;
import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.domain.RecordingUploadTask;
import io.github.jtconsole.repository.MediaRepository;
import io.github.jtconsole.repository.RecordingUploadRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RecordingUploadIngestionService {
    private static final long COMPLETION = 0x1206L;
    private static final long UPLOADED_FILE = 0xF106L;

    private final RecordingUploadRepository tasks;
    private final MediaRepository media;
    private final AuditRecorder audits;

    public RecordingUploadIngestionService(
            RecordingUploadRepository tasks, MediaRepository media, AuditRecorder audits) {
        this.tasks = tasks;
        this.media = media;
        this.audits = audits;
    }

    public boolean handle(MessageEnvelope envelope) {
        if (envelope.messageId() == null) return false;
        if (envelope.messageId() == COMPLETION) {
            handleCompletion(envelope);
            return true;
        }
        if (envelope.messageId() == UPLOADED_FILE) {
            handleFile(envelope);
            return true;
        }
        return false;
    }

    private void handleCompletion(MessageEnvelope envelope) {
        Map<String, Object> payload = envelope.payload();
        Integer serial = integer(payload, "responseSerialNo");
        Integer result = integer(payload, "result");
        if (serial == null || result == null) return;
        String now = Timestamps.normalize(envelope.receivedAt() == null ? Timestamps.now() : envelope.receivedAt());
        int updated = tasks.markTerminalCompleted(envelope.deviceId(), serial, result, now);
        if (updated == 0) return;
        tasks.findByCommandInternal(envelope.deviceId(), serial)
                .ifPresent(task -> auditCompletion(task, result, now));
    }

    private void handleFile(MessageEnvelope envelope) {
        Map<String, Object> payload = envelope.payload();
        String taskId = text(payload, "taskId");
        String fileName = text(payload, "fileName");
        Long size = number(payload, "size");
        String accessAddress = text(payload, "accessAddress");
        String contentType = text(payload, "contentType");
        if (taskId == null || fileName == null || size == null || accessAddress == null) return;
        String now = Timestamps.normalize(envelope.receivedAt() == null ? Timestamps.now() : envelope.receivedAt());
        if (tasks.attachFile(taskId, fileName, size, accessAddress, contentType, now) == 0) return;
        RecordingUploadTask task = tasks.findByIdInternal(taskId).orElse(null);
        if (task == null) return;
        media.insertIgnore(new MediaFile(
                null, task.deviceId(), stableFileId(taskId, fileName), "video",
                extension(fileName), fileName, size, accessAddress, task.channelNo(), 0,
                null, null, null, null, task.endAt()));
    }

    private void auditCompletion(RecordingUploadTask task, int result, String occurredAt) {
        audits.record(new AuditEntry(
                0, occurredAt, task.tenantId(), null, "system",
                "录像上传完成", "recording-upload", task.id(),
                "EVENT", "0x1206", "设备=" + task.deviceId() + "，结果=" + result,
                null, result == 0 ? AuditEntry.SUCCESS : AuditEntry.FAILURE,
                null, null));
    }

    private static long stableFileId(String taskId, String fileName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((taskId + '\0' + fileName).getBytes(StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? null : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
    private static Integer integer(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }
    private static Long number(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }
    private static String text(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
