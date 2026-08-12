package io.github.jtplatform.media.config;

import io.github.jtplatform.common.model.StreamKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt.media.recording")
public class RecordingProperties {
    private boolean realtimeEnabled;
    private boolean playbackEnabled;
    private boolean manualEnabled;
    private boolean alarmEnabled;
    private boolean continuousEnabled = true;
    private Path root = Path.of("recordings");
    private Duration segmentDuration = Duration.ofSeconds(30);
    private Duration searchMergeTolerance = Duration.ofSeconds(1);
    private int retentionDays;
    private long maxBytes;
    private Duration retentionCheckInterval = Duration.ofMinutes(5);
    private Path exportRoot = Path.of("recording-exports");
    private String ffmpegCommand = "ffmpeg";
    private int exportConcurrency = 2;
    private int exportQueueCapacity = 16;
    private Duration exportTimeout = Duration.ofMinutes(30);

    public boolean isRealtimeEnabled() {
        return realtimeEnabled;
    }

    public void setRealtimeEnabled(boolean realtimeEnabled) {
        this.realtimeEnabled = realtimeEnabled;
    }

    public boolean isPlaybackEnabled() {
        return playbackEnabled;
    }

    public void setPlaybackEnabled(boolean playbackEnabled) {
        this.playbackEnabled = playbackEnabled;
    }

    public boolean isManualEnabled() {
        return manualEnabled;
    }

    public void setManualEnabled(boolean manualEnabled) {
        this.manualEnabled = manualEnabled;
    }

    public boolean isAlarmEnabled() {
        return alarmEnabled;
    }

    public void setAlarmEnabled(boolean alarmEnabled) {
        this.alarmEnabled = alarmEnabled;
    }

    public boolean isContinuousEnabled() {
        return continuousEnabled;
    }

    public void setContinuousEnabled(boolean continuousEnabled) {
        this.continuousEnabled = continuousEnabled;
    }

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = root;
    }

    public Duration getSegmentDuration() {
        return segmentDuration;
    }

    public void setSegmentDuration(Duration segmentDuration) {
        this.segmentDuration = segmentDuration;
    }

    public Duration getSearchMergeTolerance() {
        return searchMergeTolerance;
    }

    public void setSearchMergeTolerance(Duration searchMergeTolerance) {
        this.searchMergeTolerance = searchMergeTolerance;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public Duration getRetentionCheckInterval() {
        return retentionCheckInterval;
    }

    public void setRetentionCheckInterval(Duration retentionCheckInterval) {
        this.retentionCheckInterval = retentionCheckInterval;
    }

    public Path getExportRoot() {
        return exportRoot;
    }

    public void setExportRoot(Path exportRoot) {
        this.exportRoot = exportRoot;
    }

    public String getFfmpegCommand() {
        return ffmpegCommand;
    }

    public void setFfmpegCommand(String ffmpegCommand) {
        this.ffmpegCommand = ffmpegCommand;
    }

    public int getExportConcurrency() {
        return exportConcurrency;
    }

    public void setExportConcurrency(int exportConcurrency) {
        this.exportConcurrency = exportConcurrency;
    }

    public int getExportQueueCapacity() {
        return exportQueueCapacity;
    }

    public void setExportQueueCapacity(int exportQueueCapacity) {
        this.exportQueueCapacity = exportQueueCapacity;
    }

    public Duration getExportTimeout() {
        return exportTimeout;
    }

    public void setExportTimeout(Duration exportTimeout) {
        this.exportTimeout = exportTimeout;
    }

    public boolean enabled() {
        return realtimeEnabled || playbackEnabled;
    }

    public boolean records(StreamKind streamKind) {
        Objects.requireNonNull(streamKind, "streamKind");
        return switch (streamKind) {
            case MAIN, SUB -> realtimeEnabled;
            case PLAYBACK -> playbackEnabled;
            case TALKBACK -> false;
        };
    }

    public void validate() {
        if (root == null) {
            throw new IllegalStateException("jt.media.recording.root must not be null");
        }
        if (segmentDuration == null || segmentDuration.isZero() || segmentDuration.isNegative()) {
            throw new IllegalStateException("jt.media.recording.segment-duration must be positive");
        }
        if (searchMergeTolerance == null || searchMergeTolerance.isNegative()) {
            throw new IllegalStateException("jt.media.recording.search-merge-tolerance must not be negative");
        }
        if (retentionDays < 0) {
            throw new IllegalStateException("jt.media.recording.retention-days must not be negative");
        }
        if (maxBytes < 0) {
            throw new IllegalStateException("jt.media.recording.max-bytes must not be negative");
        }
        if (retentionCheckInterval == null
                || retentionCheckInterval.isZero()
                || retentionCheckInterval.isNegative()) {
            throw new IllegalStateException(
                    "jt.media.recording.retention-check-interval must be positive");
        }
        if (exportRoot == null) {
            throw new IllegalStateException("jt.media.recording.export-root must not be null");
        }
        if (ffmpegCommand == null || ffmpegCommand.isBlank()) {
            throw new IllegalStateException("jt.media.recording.ffmpeg-command must not be blank");
        }
        if (ffmpegCommand.indexOf('\0') >= 0) {
            throw new IllegalStateException("jt.media.recording.ffmpeg-command contains a null character");
        }
        if (exportConcurrency <= 0) {
            throw new IllegalStateException("jt.media.recording.export-concurrency must be positive");
        }
        if (exportQueueCapacity <= 0) {
            throw new IllegalStateException("jt.media.recording.export-queue-capacity must be positive");
        }
        if (exportTimeout == null || exportTimeout.isZero() || exportTimeout.isNegative()) {
            throw new IllegalStateException("jt.media.recording.export-timeout must be positive");
        }
        try {
            if (segmentDuration.toNanos() < 1_000L) {
                throw new IllegalStateException(
                        "jt.media.recording.segment-duration must be at least one microsecond");
            }
            searchMergeTolerance.toNanos();
            retentionCheckInterval.toNanos();
            if (exportTimeout.toMillis() <= 0) {
                throw new IllegalStateException(
                        "jt.media.recording.export-timeout must be at least one millisecond");
            }
            exportTimeout.toNanos();
        } catch (ArithmeticException tooLarge) {
            throw new IllegalStateException("jt.media.recording duration is too large", tooLarge);
        }
    }

    public long segmentDurationMicros() {
        validate();
        return segmentDuration.toNanos() / 1_000L;
    }

    public long searchMergeToleranceMicros() {
        validate();
        return searchMergeTolerance.toNanos() / 1_000L;
    }

    public boolean retentionEnabled() {
        return retentionDays > 0 || maxBytes > 0;
    }
}
