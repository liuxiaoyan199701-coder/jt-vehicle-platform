package io.github.jtplatform.boot.runtime;

import io.github.jtplatform.boot.cluster.ClusterStateClientConfiguration;
import io.github.jtplatform.boot.cluster.ClusterStateServerConfiguration;
import io.github.jtplatform.boot.config.CoreRuntimeConfiguration;
import io.github.jtplatform.boot.config.JtPlatformProperties;
import io.github.jtplatform.media.config.MediaAutoConfiguration;
import io.github.jtplatform.signal.auth.DeviceAuthenticationAutoConfiguration;
import io.github.jtplatform.signal.config.SignalAutoConfiguration;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

public final class ClusterRoleApplications {
    private ClusterRoleApplications() {
    }

    public static ConfigurableApplicationContext run(RuntimeRole role, String[] args) {
        if (role == null || role == RuntimeRole.ALL) {
            throw new IllegalArgumentException("A cluster process role is required");
        }
        SpringApplication application = new SpringApplication(sourceFor(role));
        application.setAdditionalProfiles("cluster", "runtime-" + role.propertyValue());
        application.setDefaultProperties(Map.of("jt.runtime.role", role.propertyValue()));
        if (role == RuntimeRole.MEDIA) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        return application.run(args);
    }

    private static Class<?> sourceFor(RuntimeRole role) {
        return switch (role) {
            case SIGNAL -> SignalRoleConfiguration.class;
            case MEDIA -> MediaRoleConfiguration.class;
            case API -> ApiRoleConfiguration.class;
            case ALL -> throw new IllegalArgumentException("all is not a cluster process role");
        };
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("runtime-signal")
    @EnableAutoConfiguration(exclude = MediaAutoConfiguration.class)
    @EnableConfigurationProperties(JtPlatformProperties.class)
    @Import(CoreRuntimeConfiguration.class)
    public static class SignalRoleConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("runtime-media")
    @EnableAutoConfiguration(exclude = {
            SignalAutoConfiguration.class,
            DeviceAuthenticationAutoConfiguration.class
    })
    @EnableConfigurationProperties(JtPlatformProperties.class)
    @Import({ClusterStateClientConfiguration.class, CoreRuntimeConfiguration.class})
    public static class MediaRoleConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("runtime-api")
    @EnableAutoConfiguration(exclude = {
            SignalAutoConfiguration.class,
            DeviceAuthenticationAutoConfiguration.class,
            MediaAutoConfiguration.class
    })
    @EnableConfigurationProperties(JtPlatformProperties.class)
    @ComponentScan(basePackages = "io.github.jtplatform.api.stream")
    @Import({CoreRuntimeConfiguration.class, ClusterStateServerConfiguration.class})
    public static class ApiRoleConfiguration {
    }
}
