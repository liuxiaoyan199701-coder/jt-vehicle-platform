package io.github.jtplatform.boot;

import io.github.jtplatform.boot.runtime.ClusterRoleApplications;
import io.github.jtplatform.boot.runtime.RuntimeRole;
import io.github.jtplatform.boot.runtime.RuntimeRoleResolver;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "io.github.jtplatform")
@ConfigurationPropertiesScan(basePackages = "io.github.jtplatform")
public class JtPlatformApplication {
    public static void main(String[] args) {
        RuntimeRole role = RuntimeRoleResolver.resolve(args);
        if (role == RuntimeRole.ALL) {
            SpringApplication.run(JtPlatformApplication.class, args);
            return;
        }
        ClusterRoleApplications.run(role, args);
    }
}
