package io.github.jtplatform.boot.cluster;

import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import io.github.jtplatform.common.port.StreamRegistry;
import io.github.jtplatform.common.service.StreamCoordinator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("runtime-api")
public class ClusterStateServerConfiguration {
    @Bean
    ClusterStateController clusterStateController(
            MediaInstanceRegistry mediaInstances,
            StreamRegistry streams,
            StreamTokenStore tokens,
            StreamCoordinator coordinator) {
        return new ClusterStateController(mediaInstances, streams, tokens, coordinator);
    }
}
