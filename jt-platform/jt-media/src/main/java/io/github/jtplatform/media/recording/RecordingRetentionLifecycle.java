package io.github.jtplatform.media.recording;

import io.github.jtplatform.media.config.RecordingProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class RecordingRetentionLifecycle implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingRetentionLifecycle.class);

    private final RecordingRetentionService retentionService;
    private final boolean enabled;
    private final Duration interval;

    private volatile boolean running;
    private ScheduledExecutorService executor;

    public RecordingRetentionLifecycle(
            RecordingRetentionService retentionService,
            RecordingProperties properties) {
        this.retentionService = Objects.requireNonNull(retentionService, "retentionService");
        Objects.requireNonNull(properties, "properties").validate();
        this.enabled = properties.retentionEnabled();
        this.interval = properties.getRetentionCheckInterval();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (!enabled) {
            LOGGER.info("Recording retention is disabled");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("recording-retention").factory());
        long delayNanos = interval.toNanos();
        executor.scheduleWithFixedDelay(this::cleanupSafely, 0, delayNanos, TimeUnit.NANOSECONDS);
        LOGGER.info("Recording retention enabled: checkInterval={}", interval);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 200;
    }

    private void cleanupSafely() {
        try {
            retentionService.cleanup();
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Recording retention scan failed", failure);
        }
    }
}
