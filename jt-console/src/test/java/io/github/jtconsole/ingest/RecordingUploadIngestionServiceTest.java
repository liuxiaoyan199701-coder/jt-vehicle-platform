package io.github.jtconsole.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.audit.AuditRecorder;
import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.domain.RecordingUploadTask;
import io.github.jtconsole.repository.MediaRepository;
import io.github.jtconsole.repository.RecordingUploadRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecordingUploadIngestionServiceTest {
    private final RecordingUploadRepository tasks = mock(RecordingUploadRepository.class);
    private final MediaRepository media = mock(MediaRepository.class);
    private final AuditRecorder audits = mock(AuditRecorder.class);
    private final RecordingUploadIngestionService service =
            new RecordingUploadIngestionService(tasks, media, audits);

    @Test
    void uploadedFileAttachesTaskAndRegistersExistingMediaChannel() {
        when(tasks.attachFile("task-1", "evidence.mp4", 123,
                "http://media/recording-uploads/task-1/evidence.mp4", "video/mp4", now()))
                .thenReturn(1);
        when(tasks.findByIdInternal("task-1")).thenReturn(Optional.of(task()));
        MessageEnvelope envelope = new MessageEnvelope(
                "recording-upload:task-1:evidence.mp4:123", "device-1", 0xF106L,
                0, "recording-upload-v1", "2026-08-21T01:00:00Z", "media-1",
                "recording-metadata", Map.of(
                        "taskId", "task-1", "fileName", "evidence.mp4", "size", 123L,
                        "accessAddress", "http://media/recording-uploads/task-1/evidence.mp4",
                        "contentType", "video/mp4"));

        assertThat(service.handle(envelope)).isTrue();

        var file = forClass(MediaFile.class);
        verify(media).insertIgnore(file.capture());
        assertThat(file.getValue().deviceId()).isEqualTo("device-1");
        assertThat(file.getValue().fileType()).isEqualTo("video");
        assertThat(file.getValue().fileFormat()).isEqualTo("mp4");
        assertThat(file.getValue().accessAddress()).contains("recording-uploads/task-1");
    }

    private static RecordingUploadTask task() {
        return new RecordingUploadTask("task-1", 1L, "device-1", 41, 1,
                "2026-08-21T08:00:00.000+08:00", "2026-08-21T09:00:00.000+08:00",
                3, 1, 0, 7, "FILE_RECEIVED", null, null,
                "evidence.mp4", 123L, "http://media/file", "video/mp4",
                now(), now(), null);
    }

    private static String now() { return "2026-08-21T09:00:00.000+08:00"; }
}
