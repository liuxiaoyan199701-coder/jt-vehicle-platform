package io.github.jtconsole;

import io.github.jtconsole.config.ConsoleProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(ConsoleProperties.class)
@EnableScheduling
public class JtConsoleApplication {

    private static final String DEFAULT_DB_PATH = "data/jt-console.db";

    public static void main(String[] args) {
        ensureDatabaseDirectory();
        SpringApplication.run(JtConsoleApplication.class, args);
    }

    /**
     * SQLite 不会自动创建数据库文件所在的目录，父目录不存在时连接会直接失败。
     * 在 Spring 上下文启动前建好，免得部署时还要记得手工 mkdir。
     */
    private static void ensureDatabaseDirectory() {
        String dbPath = System.getenv().getOrDefault("JT_CONSOLE_DB", DEFAULT_DB_PATH);
        Path parent = Path.of(dbPath).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException failure) {
            throw new UncheckedIOException("无法创建数据库目录：" + parent, failure);
        }
    }
}
