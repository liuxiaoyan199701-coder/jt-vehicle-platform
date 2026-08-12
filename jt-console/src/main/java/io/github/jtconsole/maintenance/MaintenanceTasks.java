package io.github.jtconsole.maintenance;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.repository.EventRepository;
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
    private final ConsoleProperties properties;

    public MaintenanceTasks(
            StatusRepository statuses, EventRepository events, ConsoleProperties properties) {
        this.statuses = statuses;
        this.events = events;
        this.properties = properties;
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
