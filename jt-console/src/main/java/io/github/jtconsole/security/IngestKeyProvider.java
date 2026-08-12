package io.github.jtconsole.security;

import io.github.jtconsole.config.ConsoleProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public final class IngestKeyProvider {

    private final byte[] expectedDigest;

    public IngestKeyProvider(ConsoleProperties properties) {
        ConsoleProperties.Security security = properties.getSecurity();
        String key = security.getIngestKey() == null ? "" : security.getIngestKey();
        if (key.isBlank()) {
            if (security.isDeploymentMode()) {
                throw new IllegalStateException("部署模式必须配置独立投递密钥");
            }
            key = randomSecret();
            printLocalKey(key);
        }
        expectedDigest = digest(key);
    }

    public boolean matches(String candidate) {
        byte[] candidateDigest = digest(candidate == null ? "" : candidate);
        return MessageDigest.isEqual(expectedDigest, candidateDigest);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void printLocalKey(String key) {
        System.err.println("[jt-console] 本地开发临时投递密钥（仅本次启动有效）");
        System.err.println("[jt-console] X-JT-Ingest-Key: " + key);
    }
}
