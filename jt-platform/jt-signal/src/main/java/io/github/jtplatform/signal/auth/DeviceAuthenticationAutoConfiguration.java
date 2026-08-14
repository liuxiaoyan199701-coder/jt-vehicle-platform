package io.github.jtplatform.signal.auth;

import io.github.jtplatform.signal.config.DeviceAuthProperties;
import io.github.jtplatform.signal.config.SignalAutoConfiguration;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = SignalAutoConfiguration.class)
@EnableConfigurationProperties(DeviceAuthProperties.class)
@ConditionalOnProperty(prefix = "jt.signal", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeviceAuthenticationAutoConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceAuthenticationAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(DeviceInformationSource.class)
    DeviceInformationSource deviceInformationSource(
            DeviceAuthProperties properties,
            ObjectProvider<RemoteDeviceInformationClient> remoteClientProvider) {
        DeviceAuthMode mode = properties.getMode();
        if (mode == null) {
            throw new IllegalStateException("jt.auth.device.mode must be configured");
        }
        DeviceInformationSource source = switch (mode) {
            case ALLOW_ALL -> new AllowAllDeviceInformationSource();
            case LOCAL_LIST -> new LocalListDeviceInformationSource(properties.getLocalList());
            case REMOTE_API -> remoteSource(properties.getRemote(), remoteClientProvider);
        };
        LOGGER.info("Device authentication information source: {}", mode);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean(DeviceAuthenticationService.class)
    DeviceAuthenticationService deviceAuthenticationService(
            DeviceInformationSource informationSource,
            DeviceAuthProperties properties) {
        return new DeviceAuthenticationService(
                informationSource,
                properties.getMode(),
                properties.getRemote().getUnavailablePolicy(),
                properties.getRemote().getUnregisteredDevicePolicy());
    }

    private static DeviceInformationSource remoteSource(
            DeviceAuthProperties.Remote properties,
            ObjectProvider<RemoteDeviceInformationClient> remoteClientProvider) {
        if (properties.getUnavailablePolicy() == null) {
            throw new IllegalStateException(
                    "jt.auth.device.remote.unavailable-policy must be explicitly set to ALLOW or DENY in remote-api mode");
        }
        RemoteDeviceInformationClient client = remoteClientProvider.getIfAvailable(() -> {
            HttpRemoteDeviceInformationClient.positive(
                    properties.getConnectTimeout(), "jt.auth.device.remote.connect-timeout");
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(properties.getConnectTimeout())
                    .build();
            return new HttpRemoteDeviceInformationClient(
                    httpClient,
                    properties.getEndpoint(),
                    properties.getRequestTimeout(),
                    properties.getHeaders());
        });
        return new CachingRemoteDeviceInformationSource(
                client,
                properties.getCacheTtl(),
                properties.getCacheMaximumSize());
    }
}
