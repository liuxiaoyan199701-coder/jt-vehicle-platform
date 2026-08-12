package io.github.jtplatform.boot.cluster;

import io.github.jtplatform.boot.config.JtPlatformProperties;
import io.github.jtplatform.common.port.StreamSubscriptionPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("runtime-media")
public class ClusterStateClientConfiguration {
    @Bean
    ClusterStateHttpClient clusterStateHttpClient(JtPlatformProperties properties) {
        JtPlatformProperties.Cluster cluster = properties.getCluster();
        return new ClusterStateHttpClient(
                cluster.getApiBaseUrl(), cluster.getConnectTimeout(), cluster.getRequestTimeout());
    }

    @Bean
    @Primary
    HttpMediaInstanceRegistry clusterMediaInstanceRegistry(ClusterStateHttpClient client) {
        return new HttpMediaInstanceRegistry(client);
    }

    @Bean(destroyMethod = "close")
    @Primary
    HttpStreamRegistry clusterStreamRegistry(
            ClusterStateHttpClient client,
            JtPlatformProperties properties) {
        return new HttpStreamRegistry(client, properties.getCluster().getStatePollInterval());
    }

    @Bean
    @Primary
    HttpStreamTokenStore clusterStreamTokenStore(ClusterStateHttpClient client) {
        return new HttpStreamTokenStore(client);
    }

    @Bean
    @Primary
    StreamSubscriptionPort clusterStreamSubscriptionPort(ClusterStateHttpClient client) {
        return new HttpStreamSubscriptionPort(client);
    }
}
