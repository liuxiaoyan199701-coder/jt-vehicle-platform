package io.github.jtconsole.operations;

import io.github.jtconsole.config.ConsoleProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 升级包落盘存储。包体是二进制，落盘而非入库；文件名用 UUID，绝不采用上传原名（防目录穿越）。
 */
@Component
public class UpgradePackageStore {

    private final Path root;
    private final long maxBytes;

    public UpgradePackageStore(ConsoleProperties properties) {
        this.root = properties.getUpgrade().getDirectory().toAbsolutePath().normalize();
        this.maxBytes = properties.getUpgrade().getMaxSize().toBytes();
    }

    public long maxBytes() {
        return maxBytes;
    }

    public Stored save(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("升级包内容为空");
        }
        if (raw.length > maxBytes) {
            throw new IllegalArgumentException("升级包超过 " + (maxBytes / 1024 / 1024) + "MB 上限");
        }
        String storedName = UUID.randomUUID().toString().replace("-", "");
        try {
            Files.createDirectories(root);
            Files.write(root.resolve(storedName), raw);
        } catch (IOException failure) {
            throw new UncheckedIOException("升级包写入失败", failure);
        }
        return new Stored(storedName, sha256(raw));
    }

    public byte[] read(String storedName) {
        Path file = root.resolve(storedName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("升级包文件缺失，请重新上传");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException failure) {
            throw new UncheckedIOException("升级包读取失败", failure);
        }
    }

    public void delete(String storedName) {
        try {
            Files.deleteIfExists(root.resolve(storedName));
        } catch (IOException ignored) {
            // 删除失败不阻断元数据删除；残留文件由后续清理或人工处理。
        }
    }

    private static String sha256(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    public record Stored(String storedName, String sha256) {
    }
}
