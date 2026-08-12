package io.github.jtplatform.boot.config;

import io.github.jtplatform.common.config.AddressSource;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt")
public class JtPlatformProperties {
    private final Instance instance = new Instance();
    private final Media media = new Media();
    private final Signal signal = new Signal();
    private final Cluster cluster = new Cluster();
    private final Registry registry = new Registry();
    private final Auth auth = new Auth();

    public Instance getInstance() {
        return instance;
    }

    public Media getMedia() {
        return media;
    }

    public Signal getSignal() {
        return signal;
    }

    public Cluster getCluster() {
        return cluster;
    }

    public Registry getRegistry() {
        return registry;
    }

    public Auth getAuth() {
        return auth;
    }

    public static class Instance {
        private int number = 1;

        public int getNumber() {
            return number;
        }

        public void setNumber(int number) {
            this.number = number;
        }
    }

    public static class Media {
        private String instanceId = "media-1";
        private final ReachableAddress reachableAddress = new ReachableAddress();
        private final Capacity capacity = new Capacity();
        private Duration pendingTimeout = Duration.ofSeconds(30);
        private Duration idleTimeout = Duration.ofSeconds(60);
        private Duration heartbeatTtl = Duration.ofSeconds(15);
        private double saturationThreshold = 0.9;

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

        public Duration getPendingTimeout() {
            return pendingTimeout;
        }

        public void setPendingTimeout(Duration pendingTimeout) {
            this.pendingTimeout = pendingTimeout;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Duration getHeartbeatTtl() {
            return heartbeatTtl;
        }

        public void setHeartbeatTtl(Duration heartbeatTtl) {
            this.heartbeatTtl = heartbeatTtl;
        }

        public double getSaturationThreshold() {
            return saturationThreshold;
        }

        public void setSaturationThreshold(double saturationThreshold) {
            this.saturationThreshold = saturationThreshold;
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

    public static class Signal {
        private String instanceId = "signal-1";
        private URI commandBaseUrl = URI.create("http://127.0.0.1:7113");

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public URI getCommandBaseUrl() {
            return commandBaseUrl;
        }

        public void setCommandBaseUrl(URI commandBaseUrl) {
            this.commandBaseUrl = commandBaseUrl;
        }
    }

    public static class Cluster {
        private URI apiBaseUrl = URI.create("http://127.0.0.1:8100");
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private Duration statePollInterval = Duration.ofMillis(100);

        public URI getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(URI apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public Duration getStatePollInterval() {
            return statePollInterval;
        }

        public void setStatePollInterval(Duration statePollInterval) {
            this.statePollInterval = statePollInterval;
        }
    }

    public static class Registry {
        private RegistryType type = RegistryType.MEMORY;

        public RegistryType getType() {
            return type;
        }

        public void setType(RegistryType type) {
            this.type = type;
        }
    }

    public enum RegistryType {
        MEMORY,
        REDIS
    }

    public static class Auth {
        private final Stream stream = new Stream();

        public Stream getStream() {
            return stream;
        }
    }

    public static class Stream {
        private StreamAuthMode mode = StreamAuthMode.DISABLED;
        private URI jwksUri;
        private Duration jwksCacheTtl = Duration.ofMinutes(10);
        private Duration tokenTtl = Duration.ofSeconds(60);

        public StreamAuthMode getMode() {
            return mode;
        }

        public void setMode(StreamAuthMode mode) {
            this.mode = mode;
        }

        public URI getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(URI jwksUri) {
            this.jwksUri = jwksUri;
        }

        public Duration getJwksCacheTtl() {
            return jwksCacheTtl;
        }

        public void setJwksCacheTtl(Duration jwksCacheTtl) {
            this.jwksCacheTtl = jwksCacheTtl;
        }

        public Duration getTokenTtl() {
            return tokenTtl;
        }

        public void setTokenTtl(Duration tokenTtl) {
            this.tokenTtl = tokenTtl;
        }
    }

    public enum StreamAuthMode {
        DISABLED,
        JWT
    }
}
