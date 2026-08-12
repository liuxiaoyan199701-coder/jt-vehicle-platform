package io.github.jtconsole.security;

import io.github.jtconsole.config.ConsoleProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public final class AdminCredentialService {

    private static final Pattern BCRYPT_HASH = Pattern.compile(
            "^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private final String username;
    private final String passwordHash;
    private final PasswordEncoder passwordEncoder;

    public AdminCredentialService(
            ConsoleProperties properties,
            PasswordEncoder passwordEncoder) {
        ConsoleProperties.Security security = properties.getSecurity();
        username = requireUsername(security.getAdminUsername());
        this.passwordEncoder = passwordEncoder;

        String configuredHash = normalized(security.getAdminPasswordHash());
        if (configuredHash.isEmpty()) {
            if (security.isDeploymentMode()) {
                throw new IllegalStateException(
                        "部署模式必须配置管理员 BCrypt 密码哈希");
            }
            String oneTimePassword = randomSecret();
            passwordHash = passwordEncoder.encode(oneTimePassword);
            printLocalCredential(username, oneTimePassword);
        } else {
            if (!BCRYPT_HASH.matcher(configuredHash).matches()) {
                throw new IllegalStateException("管理员密码哈希不是有效的 BCrypt 格式");
            }
            passwordHash = configuredHash;
        }
    }

    public String username() {
        return username;
    }

    public boolean authenticate(String candidateUsername, String candidatePassword) {
        String safeUsername = candidateUsername == null ? "" : candidateUsername;
        String safePassword = candidatePassword == null ? "" : candidatePassword;

        boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(safePassword, passwordHash);
        } catch (IllegalArgumentException invalidHash) {
            passwordMatches = false;
        }
        boolean usernameMatches = MessageDigest.isEqual(
                username.getBytes(StandardCharsets.UTF_8),
                safeUsername.getBytes(StandardCharsets.UTF_8));
        return usernameMatches & passwordMatches;
    }

    private static String requireUsername(String value) {
        String username = normalized(value);
        if (username.isEmpty()) {
            throw new IllegalStateException("管理员用户名不能为空");
        }
        return username;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void printLocalCredential(String username, String password) {
        System.err.println("[jt-console] 本地开发一次性管理员凭据（仅本次启动有效）");
        System.err.println("[jt-console] 用户名: " + username);
        System.err.println("[jt-console] 密码: " + password);
    }
}
