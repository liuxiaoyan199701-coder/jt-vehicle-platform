package io.github.jtconsole.maintenance;

import io.github.jtconsole.ai.vision.AttachmentStore;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.repository.AiConversationRepository;
import io.github.jtconsole.repository.EventRepository;
import io.github.jtconsole.repository.ConnectionEventRepository;
import io.github.jtconsole.repository.DeviceLogRepository;
import io.github.jtconsole.repository.StatusRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceTasks {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceTasks.class);

    private final StatusRepository statuses;
    private final EventRepository events;
    private final AttachmentStore attachments;
    private final AiConversationRepository conversations;
    private final ConsoleProperties properties;
    private final ConnectionEventRepository connectionEvents;
    private final DeviceLogRepository deviceLogs;

    public MaintenanceTasks(
            StatusRepository statuses,
            EventRepository events,
            AttachmentStore attachments,
            AiConversationRepository conversations,
            ConsoleProperties properties,
            ConnectionEventRepository connectionEvents,
            DeviceLogRepository deviceLogs) {
        this.statuses = statuses;
        this.events = events;
        this.attachments = attachments;
        this.conversations = conversations;
        this.properties = properties;
        this.connectionEvents = connectionEvents;
        this.deviceLogs = deviceLogs;
    }

    /** 连接事件保留 14 天，分钟错开其它清理任务，避免 SQLite 写锁同一时刻竞争。 */
    @Scheduled(cron = "${jt.console.connection-diagnostics.cleanup-cron:0 41 3 * * *}")
    public void purgeConnectionEvents() {
        var config = properties.getConnectionDiagnostics();
        Instant cutoff = Instant.now().minus(config.getRetention());
        int total = 0;
        for (int batch = 0; batch < config.getCleanupMaxBatches(); batch++) {
            int removed = connectionEvents.deleteOlderThan(cutoff, config.getCleanupBatchSize());
            total += removed;
            if (removed < config.getCleanupBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            LOGGER.info("清理 {} 条过期连接诊断事件", total);
        }
    }

    /**
     * 设备报文日志保留 14 天。
     *
     * <p>与连接事件（3:41）、审计（3:17）错峰到 3:53：日志库虽是独立文件，磁盘 IO 仍会和业务写抢资源。
     * 一次失败只记 warn——定时任务不能因为一次锁冲突就永久停摆，下一轮继续从最旧的批次删起。
     */
    @Scheduled(cron = "${jt.console.device-log.cleanup-cron:0 53 3 * * *}")
    public void purgeDeviceLogs() {
        ConsoleProperties.DeviceLog config = properties.getDeviceLog();
        Instant cutoff = Instant.now().minus(config.getRetention());
        int total = 0;
        try {
            for (int batch = 0; batch < config.getCleanupMaxBatches(); batch++) {
                int removed = deviceLogs.deleteOlderThan(cutoff, config.getCleanupBatchSize());
                total += removed;
                if (removed < config.getCleanupBatchSize()) {
                    break;
                }
            }
            if (total > 0) {
                LOGGER.info("清理 {} 条过期设备日志", total);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("设备日志保留期清理失败：{}", failure.getClass().getSimpleName());
        }
    }

    /**
     * 按最后更新时间分批清理过期 AI 会话及消息。计量表不参与清理，保留计费依据。
     *
     * <p>复用 AI 已有的凌晨 3:41 cleanupCron，并限制单批与总批数，避免 SQLite 长事务锁库。
     */
    @Scheduled(cron = "${jt.console.ai.cleanup-cron:0 41 3 * * *}")
    public void purgeExpiredConversations() {
        ConsoleProperties.Ai ai = properties.getAi();
        String cutoff = Timestamps.of(Instant.now().minus(ai.getConversationRetention()));
        int total = 0;
        int orphanMessages = 0;
        try {
            for (int batch = 0; batch < ai.getConversationCleanupMaxBatches(); batch++) {
                int removed = conversations.deleteOlderThan(
                        cutoff, ai.getConversationCleanupBatchSize());
                total += removed;
                if (removed < ai.getConversationCleanupBatchSize()) {
                    break;
                }
            }
            for (int batch = 0; batch < ai.getConversationCleanupMaxBatches(); batch++) {
                int removed = conversations.deleteOrphanMessages(
                        ai.getConversationCleanupBatchSize());
                orphanMessages += removed;
                if (removed < ai.getConversationCleanupBatchSize()) {
                    break;
                }
            }
            if (total > 0 || orphanMessages > 0) {
                LOGGER.info("清理了 {} 个早于 {} 的 AI 对话及其消息，并移除 {} 条孤儿消息",
                        total, cutoff, orphanMessages);
            }
        } catch (RuntimeException failure) {
            // 定时任务一次失败不能永久停摆；下一次调度继续从最旧批次重试。
            LOGGER.warn("AI 对话保留期清理失败：{}", failure.getClass().getSimpleName());
        }
    }

    /**
     * 清理过期的对话图片附件。
     *
     * <p>低频即可：图片只是对话的输入，识别出的描述已经作为文字固化在消息里，原图过期删掉
     * 不损失任何信息。放在凌晨且错开其它清理任务——SQLite 只有一个写锁，虽然本任务只动文件系统，
     * 但磁盘 IO 同样会和业务写入抢资源。
     */
    @Scheduled(cron = "${jt.console.ai.attachment.cleanup-cron:0 53 4 * * *}")
    public void purgeExpiredAttachments() {
        Instant cutoff = Instant.now().minus(properties.getAi().getAttachment().getRetention());
        int removed = attachments.purgeOlderThan(cutoff);
        if (removed > 0) {
            LOGGER.info("清理了 {} 个账号早于 {} 的对话图片附件", removed, cutoff);
        }
    }

    /**
     * 离线判定。设备不会主动告知下线，只能靠「多久没收到消息」推断。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void markOfflineDevices() {
        Instant cutoff = Instant.now().minus(properties.getOfflineTimeout());
        int offline = statuses.markOfflineBefore(cutoff);
        if (offline > 0) {
            LOGGER.info("Marked {} device(s) offline (no report since {})", offline, cutoff);
        }
    }

    /**
     * 清理幂等表。保留窗口只需覆盖网关的重投递窗口即可。
     */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 300_000L)
    public void purgeProcessedEvents() {
        Instant cutoff = Instant.now().minus(properties.getEventRetention());
        int purged = events.deleteOlderThan(cutoff);
        if (purged > 0) {
            LOGGER.info("Purged {} processed event record(s) older than {}", purged, cutoff);
        }
    }
}
