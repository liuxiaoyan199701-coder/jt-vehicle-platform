package io.github.jtconsole.security;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.Account;
import io.github.jtconsole.domain.Role;
import io.github.jtconsole.repository.AccountRepository;
import io.github.jtconsole.repository.RoleRepository;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号表为空时，用环境变量里的管理员凭据创建首个平台管理员。
 *
 * <p>这是环境变量凭据仅剩的用途：引导完成后认证只认数据库账号。两套真值来源并存会在改密后
 * 留下「旧的环境变量密码仍能登录」的后门，因此引导后 MUST NOT 再参与认证判定。
 */
@Component
public class AdminAccountBootstrap implements InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAccountBootstrap.class);
    private static final Pattern BCRYPT_HASH = Pattern.compile(
            "^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private final ConsoleProperties properties;
    private final AccountRepository accounts;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;

    public AdminAccountBootstrap(
            ConsoleProperties properties,
            AccountRepository accounts,
            RoleRepository roles,
            PasswordEncoder passwordEncoder,
            PermissionCatalogSynchronizer catalog) {
        this.properties = properties;
        this.accounts = accounts;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        // 仅用于强制 bean 创建顺序：内置角色必须先存在才能绑定。
        java.util.Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    @Transactional
    public void afterPropertiesSet() {
        if (!accounts.isEmpty()) {
            return;
        }
        ConsoleProperties.Security security = properties.getSecurity();
        String username = normalized(security.getAdminUsername());
        if (username.isEmpty()) {
            throw new IllegalStateException("管理员用户名不能为空");
        }

        String passwordHash = resolvePasswordHash(security, username);
        String now = Timestamps.now();
        long accountId = accounts.insert(new Account(
                0L, username, passwordHash, "平台管理员",
                null, null, null, Account.ACTIVE, null, now, now));

        long platformAdminRoleId = roles.findBuiltin(Role.PLATFORM_ADMIN)
                .map(Role::id)
                .orElseThrow(() -> new IllegalStateException("内置平台管理员角色缺失，无法完成引导"));
        roles.replaceAccountRoles(accountId, List.of(platformAdminRoleId));
        LOGGER.info("已用配置的管理员凭据引导首个平台管理员账号");
    }

    private String resolvePasswordHash(ConsoleProperties.Security security, String username) {
        String configuredHash = normalized(security.getAdminPasswordHash());
        if (!configuredHash.isEmpty()) {
            if (!BCRYPT_HASH.matcher(configuredHash).matches()) {
                throw new IllegalStateException("管理员密码哈希不是有效的 BCrypt 格式");
            }
            return configuredHash;
        }
        if (security.isDeploymentMode()) {
            throw new IllegalStateException("部署模式必须配置管理员 BCrypt 密码哈希");
        }
        String oneTimePassword = randomSecret();
        printLocalCredential(username, oneTimePassword);
        return passwordEncoder.encode(oneTimePassword);
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
        System.err.println("[jt-console] 本地开发引导管理员凭据（此后由数据库账号接管）");
        System.err.println("[jt-console] 用户名: " + username);
        System.err.println("[jt-console] 密码: " + password);
    }
}
