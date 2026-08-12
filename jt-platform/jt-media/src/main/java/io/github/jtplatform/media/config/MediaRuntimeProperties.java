package io.github.jtplatform.media.config;

import io.github.jtplatform.common.config.AddressSource;
import io.github.jtplatform.common.config.ReachableAddressSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt.media")
public class MediaRuntimeProperties {
    private String instanceId = "media-1";
    private final ReachableAddress reachableAddress = new ReachableAddress();
    private final Capacity capacity = new Capacity();
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    private Duration heartbeatTtl = Duration.ofSeconds(15);

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public ReachableAddress getReachableAddress() {
        return reachableAddress;
    }

    public Capacity getCapacity() {
        return capacity;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Duration getHeartbeatTtl() {
        return heartbeatTtl;
    }

    public void setHeartbeatTtl(Duration heartbeatTtl) {
        this.heartbeatTtl = heartbeatTtl;
    }

    public void validate() {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalStateException("jt.media.instance-id must not be blank");
        }
        if (capacity.maxStreams < 1 || capacity.maxOutboundBitsPerSecond < 1) {
            throw new IllegalStateException("Media capacity limits must be positive");
        }
        if (heartbeatInterval == null || heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalStateException("jt.media.heartbeat-interval must be positive");
        }
        if (heartbeatTtl == null || heartbeatTtl.isZero() || heartbeatTtl.isNegative()) {
            throw new IllegalStateException("jt.media.heartbeat-ttl must be positive");
        }
        if (heartbeatInterval.compareTo(heartbeatTtl) >= 0) {
            throw new IllegalStateException("jt.media.heartbeat-interval must be shorter than heartbeat-ttl");
        }
    }

    public static class ReachableAddress {
        private AddressSource source = AddressSource.AUTO;
        private String value = "";
        private String envName = "JT_REACHABLE_ADDRESS";

        public AddressSource getSource() {
            return source;
        }

        public void setSource(AddressSource source) {
            this.source = source;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public String getEnvName() {
            return envName;
        }

        public void setEnvName(String envName) {
            this.envName = envName;
        }

        public ReachableAddressSettings toSettings() {
            return new ReachableAddressSettings(source, value, envName);
        }
    }

    public static class Capacity {
        private int maxStreams = 1000;
        private long maxOutboundBitsPerSecond = 1_000_000_000L;

        public int getMaxStreams() {
            return maxStreams;
        }

        public void setMaxStreams(int maxStreams) {
            this.maxStreams = maxStreams;
        }

        public long getMaxOutboundBitsPerSecond() {
            return maxOutboundBitsPerSecond;
        }

        public void setMaxOutboundBitsPerSecond(long maxOutboundBitsPerSecond) {
            this.maxOutboundBitsPerSecond = maxOutboundBitsPerSecond;
        }
    }
}
