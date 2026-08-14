package io.github.jtconsole.security;

import io.github.jtconsole.domain.Account;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.repository.AccountRepository;
import io.github.jtconsole.repository.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 数据库账号认证。
 *
 * <p>「用户名不存在」「密码错误」「账号被禁用」「租户停用或到期」「注册待审批」
 * 一律返回同一个空结果：任何差异化的失败语义都会把登录页变成账户与租户状态的枚举器。
 * 用户名不存在时仍执行一次 BCrypt 比对，避免用响应时间区分账号是否存在。
 */
@Service
public class AccountAuthenticationService {

    /**
     * 用户名不存在时用于消耗等量算力的占位哈希（对应一个不可能被猜到的随机密码）。
     * 取值本身无意义，只要是合法 BCrypt 串即可。
     */
    private static final String TIMING_EQUALIZER_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final AccountRepository accounts;
    private final TenantRepository tenants;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Autowired
    public AccountAuthenticationService(
            AccountRepository accounts,
            TenantRepository tenants,
            PasswordEncoder passwordEncoder) {
        this(accounts, tenants, passwordEncoder, Clock.systemUTC());
    }

    AccountAuthenticationService(
            AccountRepository accounts,
            TenantRepository tenants,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.accounts = accounts;
        this.tenants = tenants;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** 认证成功返回账号，任何失败原因都返回空。 */
    public Optional<Account> authenticate(String username, String password) {
        String safeUsername = username == null ? "" : username.trim();
        String safePassword = password == null ? "" : password;

        Optional<Account> candidate = safeUsername.isEmpty()
                ? Optional.empty()
                : accounts.findByUsername(safeUsername);
        String hash = candidate.map(Account::passwordHash).orElse(TIMING_EQUALIZER_HASH);

        boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(safePassword, hash);
        } catch (IllegalArgumentException invalidHash) {
            passwordMatches = false;
        }
        if (candidate.isEmpty() || !passwordMatches) {
            return Optional.empty();
        }

        Account account = candidate.get();
        if (!constantTimeEquals(account.username(), safeUsername) || !account.enabled()) {
            return Optional.empty();
        }
        if (!tenantUsable(account)) {
            return Optional.empty();
        }
        return Optional.of(account);
    }

    /** 校验账号当前密码，用于「修改自己密码」。 */
    public boolean passwordMatches(Account account, String candidatePassword) {
        try {
            return passwordEncoder.matches(
                    candidatePassword == null ? "" : candidatePassword, account.passwordHash());
        } catch (IllegalArgumentException invalidHash) {
            return false;
        }
    }

    public void recordSuccessfulLogin(long accountId) {
        accounts.recordLogin(accountId, clock.instant());
    }

    /** 平台账号无租户约束；租户账号要求租户存在、状态为启用且未到期。 */
    private boolean tenantUsable(Account account) {
        if (account.platformAccount()) {
            return true;
        }
        Instant now = clock.instant();
        return tenants.findById(account.tenantId())
                .map(tenant -> tenant.active(now))
                .orElse(false);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    /** 便于服务层复用的租户可用性判定。 */
    public boolean tenantActive(Tenant tenant) {
        return tenant != null && tenant.active(clock.instant());
    }
}
