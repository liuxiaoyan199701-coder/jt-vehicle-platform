package io.github.jtplatform.delivery.config;

import io.github.jtplatform.delivery.channel.AsyncChannelOptions;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jt.delivery")
public class DeliveryProperties {
    private Set<DeliveryChannelKind> channels = EnumSet.noneOf(DeliveryChannelKind.class);
    private final Api api = new Api();
    private final RocketMq rocketMq = new RocketMq();

    public Set<DeliveryChannelKind> getChannels() {
        return Collections.unmodifiableSet(channels);
    }

    public void setChannels(Set<DeliveryChannelKind> channels) {
        if (channels == null || channels.isEmpty()) {
            this.channels = EnumSet.noneOf(DeliveryChannelKind.class);
        } else {
            this.channels = EnumSet.copyOf(channels);
        }
    }

    public Api getApi() {
        return api;
    }

    public RocketMq getRocketMq() {
        return rocketMq;
    }

    public boolean isEnabled(DeliveryChannelKind channel) {
        return channels.contains(channel);
    }

    public static class Channel {
        private int queueCapacity = 1024;
        private int stripes = 4;
        private Integer criticalReserve;
        private int circuitFailureThreshold = 5;
        private Duration circuitOpenDuration = Duration.ofSeconds(30);
        private Duration initialRetryDelay = Duration.ofMillis(200);
        private Duration maximumRetryDelay = Duration.ofSeconds(30);
        private Duration sendTimeout = Duration.ofSeconds(10);
        private Duration shutdownTimeout = Duration.ofSeconds(5);
        private Path overflowDirectory = Path.of("data", "delivery-overflow");

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getStripes() {
            return stripes;
        }

        public void setStripes(int stripes) {
            this.stripes = stripes;
        }

        public Integer getCriticalReserve() {
            return criticalReserve;
        }

        public void setCriticalReserve(Integer criticalReserve) {
            this.criticalReserve = criticalReserve;
        }

        public int getCircuitFailureThreshold() {
            return circuitFailureThreshold;
        }

        public void setCircuitFailureThreshold(int circuitFailureThreshold) {
            this.circuitFailureThreshold = circuitFailureThreshold;
        }

        public Duration getCircuitOpenDuration() {
            return circuitOpenDuration;
        }

        public void setCircuitOpenDuration(Duration circuitOpenDuration) {
            this.circuitOpenDuration = circuitOpenDuration;
        }

        public Duration getInitialRetryDelay() {
            return initialRetryDelay;
        }

        public void setInitialRetryDelay(Duration initialRetryDelay) {
            this.initialRetryDelay = initialRetryDelay;
        }

        public Duration getMaximumRetryDelay() {
            return maximumRetryDelay;
        }

        public void setMaximumRetryDelay(Duration maximumRetryDelay) {
            this.maximumRetryDelay = maximumRetryDelay;
        }

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }

        public Duration getShutdownTimeout() {
            return shutdownTimeout;
        }

        public void setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
        }

        public Path getOverflowDirectory() {
            return overflowDirectory;
        }

        public void setOverflowDirectory(Path overflowDirectory) {
            this.overflowDirectory = Objects.requireNonNull(overflowDirectory, "overflowDirectory");
        }

        public AsyncChannelOptions toOptions() {
            int effectiveStripes = Math.min(stripes, queueCapacity);
            int effectiveReserve = criticalReserve == null ? Math.max(1, queueCapacity / 16) : criticalReserve;
            return new AsyncChannelOptions(queueCapacity, effectiveStripes, effectiveReserve, circuitFailureThreshold,
                    circuitOpenDuration, initialRetryDelay, maximumRetryDelay, sendTimeout, shutdownTimeout,
                    overflowDirectory);
        }
    }

    public static final class Api extends Channel {
        private URI endpoint;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Map<String, String> headers = Map.of();

        public URI getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(URI endpoint) {
            this.endpoint = endpoint;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(headers, "headers")));
        }
    }

    public static final class RocketMq extends Channel {
        private String topic = "jt-messages";
        private String nameServer = "127.0.0.1:9876";
        private String producerGroup = "jt-platform-producer";
        private String namespace = "";

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getNameServer() {
            return nameServer;
        }

        public void setNameServer(String nameServer) {
            this.nameServer = nameServer;
        }

        public String getProducerGroup() {
            return producerGroup;
        }

        public void setProducerGroup(String producerGroup) {
            this.producerGroup = producerGroup;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }
    }
}
