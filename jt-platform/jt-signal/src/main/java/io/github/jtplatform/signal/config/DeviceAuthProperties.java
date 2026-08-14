package io.github.jtplatform.signal.config;

import io.github.jtplatform.signal.auth.DeviceAuthMode;
import io.github.jtplatform.signal.auth.RemoteUnavailablePolicy;
import io.github.jtplatform.signal.auth.UnregisteredDevicePolicy;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt.auth.device")
public class DeviceAuthProperties {
    private DeviceAuthMode mode = DeviceAuthMode.ALLOW_ALL;
    private Set<String> localList = new LinkedHashSet<>();
    private final Remote remote = new Remote();

    public DeviceAuthMode getMode() {
        return mode;
    }

    public void setMode(DeviceAuthMode mode) {
        this.mode = mode;
    }

    public Set<String> getLocalList() {
        return Set.copyOf(localList);
    }

    public void setLocalList(Set<String> localList) {
        this.localList = localList == null ? new LinkedHashSet<>() : new LinkedHashSet<>(localList);
    }

    public Remote getRemote() {
        return remote;
    }

    public static class Remote {
        private URI endpoint;
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration requestTimeout = Duration.ofSeconds(3);
        private Duration cacheTtl = Duration.ofMinutes(5);
        private long cacheMaximumSize = 100_000;
        private RemoteUnavailablePolicy unavailablePolicy;
        /**
         * What to do with a device the remote source has no archive for. Defaults to REJECT,
         * matching what remote-api mode has always done; set ALLOW where devices are commissioned
         * in the field before they are archived in the console.
         */
        private UnregisteredDevicePolicy unregisteredDevicePolicy = UnregisteredDevicePolicy.REJECT;
        private Map<String, String> headers = new LinkedHashMap<>();

        public UnregisteredDevicePolicy getUnregisteredDevicePolicy() {
            return unregisteredDevicePolicy;
        }

        public void setUnregisteredDevicePolicy(UnregisteredDevicePolicy unregisteredDevicePolicy) {
            this.unregisteredDevicePolicy = unregisteredDevicePolicy == null
                    ? UnregisteredDevicePolicy.REJECT
                    : unregisteredDevicePolicy;
        }

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
            this.connectTimeout = connectTimeout;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }

        public long getCacheMaximumSize() {
            return cacheMaximumSize;
        }

        public void setCacheMaximumSize(long cacheMaximumSize) {
            this.cacheMaximumSize = cacheMaximumSize;
        }

        public RemoteUnavailablePolicy getUnavailablePolicy() {
            return unavailablePolicy;
        }

        public void setUnavailablePolicy(RemoteUnavailablePolicy unavailablePolicy) {
            this.unavailablePolicy = unavailablePolicy;
        }

        public Map<String, String> getHeaders() {
            return Map.copyOf(headers);
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        }
    }
}
