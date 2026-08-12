package io.github.jtplatform.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeploymentShapeIsolationTest {
    @Test
    void businessModulesDoNotDependOnRuntimeRoleOrBootComposition() throws IOException {
        Path reactorRoot = reactorRoot();
        for (String module : List.of("jt-signal", "jt-media", "jt-api")) {
            Path sourceRoot = reactorRoot.resolve(module).resolve("src/main/java");
            assertTrue(Files.isDirectory(sourceRoot), "Missing source directory: " + sourceRoot);
            try (var files = Files.walk(sourceRoot)) {
                for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String content = Files.readString(source);
                    assertFalse(content.contains("io.github.jtplatform.boot"),
                            () -> source + " must not depend on jt-boot-all");
                    assertFalse(content.contains("jt.runtime.role"),
                            () -> source + " must not inspect the deployment role");
                    assertFalse(content.contains("RuntimeRole"),
                            () -> source + " must not inspect the deployment role type");
                }
            }
        }
    }

    private static Path reactorRoot() {
        Path configured = Path.of(System.getProperty("maven.multiModuleProjectDirectory", "."))
                .toAbsolutePath()
                .normalize();
        if (Files.isDirectory(configured.resolve("jt-signal"))) {
            return configured;
        }
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("jt-signal"))) {
            return workingDirectory;
        }
        return workingDirectory.getParent();
    }
}
