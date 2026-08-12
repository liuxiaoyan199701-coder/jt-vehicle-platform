package io.github.jtconsole.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jtconsole.config.ConsoleProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecurityConfigurationPropertiesTest {

    @Test
    void localOriginsUseTheActualFrontendDevelopmentPort() {
        ConsoleProperties properties = new ConsoleProperties();

        assertThat(properties.getSecurity().getAllowedOrigins())
                .containsExactly(
                        "http://localhost:9527",
                        "http://127.0.0.1:9527");
        assertThat(properties.getBroadcast().getDispatchQueueCapacity()).isPositive();
        assertThat(properties.getBroadcast().getSessionQueueCapacity()).isPositive();
        assertThat(properties.getBroadcast().getWorkerThreads()).isPositive();
        assertThat(properties.getBroadcast().getSendTimeout()).isPositive();
    }

    @Test
    void deploymentModeRejectsMissingAdministratorHash() {
        ConsoleProperties properties = deployedProperties();

        assertThatThrownBy(() -> new AdminCredentialService(
                        properties, new BCryptPasswordEncoder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BCrypt");
    }

    @Test
    void deploymentModeRejectsMissingIngestKey() {
        ConsoleProperties properties = deployedProperties();

        assertThatThrownBy(() -> new IngestKeyProvider(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("投递密钥");
    }

    private static ConsoleProperties deployedProperties() {
        ConsoleProperties properties = new ConsoleProperties();
        properties.getSecurity().setDeploymentMode(true);
        properties.getSecurity().setAllowedOrigins(List.of("https://console.example.test"));
        return properties;
    }
}
