package io.github.jtplatform.simulator.config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public final class ConfigStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONFIG_FILE = "config.json";

    private final Path configFile;

    public ConfigStore() {
        this(defaultDirectory(System.getenv(), System.getProperty("user.home")));
    }

    public ConfigStore(Path directory) {
        this.configFile = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize()
                .resolve(CONFIG_FILE);
    }

    public SimulatorConfig load() throws IOException {
        if (!Files.isRegularFile(configFile)) {
            return SimulatorConfig.defaults();
        }
        return JSON.readValue(Files.readAllBytes(configFile), SimulatorConfig.class);
    }

    public void save(SimulatorConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        Path directory = configFile.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "config-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(config));
            try {
                Files.move(temporary, configFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public Path configFile() {
        return configFile;
    }

    static Path defaultDirectory(Map<String, String> environment, String userHome) {
        String localAppData = environment.get("LOCALAPPDATA");
        Path base;
        if (localAppData != null && !localAppData.isBlank()) {
            base = Path.of(localAppData.trim());
        } else {
            String home = Objects.requireNonNull(userHome, "userHome");
            base = Path.of(home, "AppData", "Local");
        }
        return base.resolve("JTPlatform").resolve("terminal-simulator");
    }
}
