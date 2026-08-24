package io.github.jtconsole.maintenance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jtconsole.ai.vision.AttachmentStore;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.repository.AiConversationRepository;
import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.repository.DeviceLogRepository;
import io.github.jtconsole.repository.EventRepository;
import io.github.jtconsole.repository.StatusRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MaintenanceTasksTest {

    private final AiConversationRepository conversations = mock(AiConversationRepository.class);
    private final DeviceLogRepository deviceLogs = mock(DeviceLogRepository.class);
    private final ConsoleProperties properties = new ConsoleProperties();
    private final MaintenanceTasks tasks = new MaintenanceTasks(
            mock(StatusRepository.class), mock(EventRepository.class), mock(AttachmentStore.class),
            conversations, properties, mock(ConnectionEventRepository.class), deviceLogs);

    @Test
    void conversationCleanupUsesConfiguredBatchSizeUntilAShortBatch() {
        properties.getAi().setConversationRetention(Duration.ofDays(30));
        properties.getAi().setConversationCleanupBatchSize(2);
        properties.getAi().setConversationCleanupMaxBatches(10);
        when(conversations.deleteOlderThan(anyString(), org.mockito.ArgumentMatchers.eq(2)))
                .thenReturn(2, 2, 1);
        when(conversations.deleteOrphanMessages(2)).thenReturn(0);

        tasks.purgeExpiredConversations();

        verify(conversations, times(3)).deleteOlderThan(anyString(), org.mockito.ArgumentMatchers.eq(2));
        verify(conversations).deleteOrphanMessages(2);
    }

    @Test
    void conversationCleanupNeverExceedsConfiguredMaximumBatches() {
        properties.getAi().setConversationCleanupBatchSize(1);
        properties.getAi().setConversationCleanupMaxBatches(3);
        when(conversations.deleteOlderThan(anyString(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(1);
        when(conversations.deleteOrphanMessages(1)).thenReturn(1);

        tasks.purgeExpiredConversations();

        verify(conversations, times(3)).deleteOlderThan(anyString(), org.mockito.ArgumentMatchers.eq(1));
        verify(conversations, times(3)).deleteOrphanMessages(1);
    }

    @Test
    void deviceLogCleanupDeletesInBatchesUntilAShortBatchEndsIt() {
        properties.getDeviceLog().setCleanupBatchSize(2);
        properties.getDeviceLog().setCleanupMaxBatches(10);
        when(deviceLogs.deleteOlderThan(any(Instant.class), eq(2))).thenReturn(2, 2, 1);

        tasks.purgeDeviceLogs();

        verify(deviceLogs, times(3)).deleteOlderThan(any(Instant.class), eq(2));
    }

    @Test
    void deviceLogCleanupStopsAtTheConfiguredCeilingInsteadOfHoldingTheWriteLock() {
        properties.getDeviceLog().setCleanupBatchSize(1);
        properties.getDeviceLog().setCleanupMaxBatches(3);
        when(deviceLogs.deleteOlderThan(any(Instant.class), eq(1))).thenReturn(1);

        tasks.purgeDeviceLogs();

        verify(deviceLogs, times(3)).deleteOlderThan(any(Instant.class), eq(1));
    }

    /** 一次失败不能让清理永久停摆——下一轮调度继续从最旧的批次删起。 */
    @Test
    void aLockedDeviceLogDatabaseOnlyWarnsAndLetsTheNextRunRetry() {
        when(deviceLogs.deleteOlderThan(any(Instant.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("database is locked"));

        tasks.purgeDeviceLogs();

        verify(deviceLogs).deleteOlderThan(any(Instant.class), org.mockito.ArgumentMatchers.anyInt());
    }
}
