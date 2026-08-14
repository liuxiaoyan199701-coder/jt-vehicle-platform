package io.github.jtconsole.iam;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.Account;
import io.github.jtconsole.domain.Plan;
import io.github.jtconsole.domain.Role;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.domain.TenantRegistration;
import io.github.jtconsole.domain.TenantStatus;
import io.github.jtconsole.repository.AccountRepository;
import io.github.jtconsole.repository.PlanRepository;
import io.github.jtconsole.repository.RegistrationRepository;
import io.github.jtconsole.repository.RoleRepository;
import io.github.jtconsole.repository.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户自助注册与平台审批。
 *
 * <p>注册不等于开通：提交后租户处于待审批、管理员账号处于禁用，既不能登录也不占配额统计。
 * 设备接入是付费能力，必须过平台这一关。
 */
@Service
public class RegistrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationService.class);
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final ConsoleProperties properties;
    private final TenantRepository tenants;
    private final AccountRepository accounts;
    private final RoleRepository roles;
    private final PlanRepository plans;
    private final RegistrationRepository registrations;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaService captcha;
    private final Clock clock;

    @Autowired
    public RegistrationService(
            ConsoleProperties properties,
            TenantRepository tenants,
            AccountRepository accounts,
            RoleRepository roles,
            PlanRepository plans,
            RegistrationRepository registrations,
            PasswordEncoder passwordEncoder,
            CaptchaService captcha) {
        this(properties, tenants, accounts, roles, plans, registrations,
                passwordEncoder, captcha, Clock.systemUTC());
    }

    RegistrationService(
            ConsoleProperties properties,
            TenantRepository tenants,
            AccountRepository accounts,
            RoleRepository roles,
            PlanRepository plans,
            RegistrationRepository registrations,
            PasswordEncoder passwordEncoder,
            CaptchaService captcha,
            Clock clock) {
        this.properties = properties;
        this.tenants = tenants;
        this.accounts = accounts;
        this.roles = roles;
        this.plans = plans;
        this.registrations = registrations;
        this.passwordEncoder = passwordEncoder;
        this.captcha = captcha;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.getRegistration().isEnabled();
    }

    public CaptchaService.Challenge issueCaptcha() {
        requireEnabled();
        return captcha.issue();
    }

    @Transactional
    public void submit(RegistrationRequest request, String sourceIp) {
        requireEnabled();
        if (!captcha.verifyAndConsume(request.captchaToken(), request.captchaCode())) {
            throw IamException.invalid("验证码不正确或已过期");
        }

        String companyName = requireText(request.companyName(), "企业名称", 100);
        String contactName = requireText(request.contactName(), "联系人", 50);
        String contactPhone = requireText(request.contactPhone(), "联系电话", 50);
        String username = requireText(request.username(), "管理员用户名", 64);
        String password = request.password() == null ? "" : request.password();
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw IamException.invalid("密码至少 " + MIN_PASSWORD_LENGTH + " 个字符");
        }
        if (accounts.usernameExists(username) || tenants.nameExists(companyName)) {
            // 用户名与企业名共用同一条提示：分开提示会让公网入口变成账号存在性探测器。
            throw IamException.conflict("该企业名称或用户名已被占用");
        }

        String now = Instant.now().toString();
        String code = generateTenantCode(companyName);
        long tenantId = tenants.insert(new Tenant(
                0L, code, companyName, TenantStatus.PENDING_APPROVAL.name(), null, null,
                contactName, contactPhone, "自助注册待审批", now, now));
        long accountId = accounts.insert(new Account(
                0L, username, passwordEncoder.encode(password), contactName,
                tenantId, null, null, Account.DISABLED, null, now, now));
        long tenantAdminRoleId = roles.findBuiltin(Role.TENANT_ADMIN)
                .map(Role::id)
                .orElseThrow(() -> new IllegalStateException("内置租户管理员角色缺失"));
        roles.replaceAccountRoles(accountId, List.of(tenantAdminRoleId));
        registrations.insert(new TenantRegistration(
                0L, tenantId, accountId, companyName, contactName, contactPhone, username,
                TenantRegistration.PENDING, null, null, null, sourceIp, now, now));
        LOGGER.info("收到租户注册申请：{}", code);
    }

    @Transactional(readOnly = true)
    public List<TenantRegistration> list(String status) {
        return registrations.findByStatus(status);
    }

    @Transactional
    public void approve(long registrationId, Long planId, Integer months, String reviewer) {
        TenantRegistration registration = require(registrationId);
        if (!TenantRegistration.PENDING.equals(registration.status())) {
            throw IamException.conflict("该申请已处理");
        }
        Plan plan = planId == null ? null : plans.findById(planId)
                .orElseThrow(() -> IamException.notFound("套餐不存在"));

        String expiresAt = null;
        if (plan != null) {
            int period = months != null && months > 0 ? months : plan.periodMonths();
            expiresAt = clock.instant().atZone(ZoneOffset.UTC)
                    .plusMonths(period).toInstant().toString();
        }
        tenants.updateExpiry(registration.tenantId(), plan == null ? null : plan.id(), expiresAt);
        tenants.updateStatus(registration.tenantId(), TenantStatus.ACTIVE);
        accounts.updateStatus(registration.accountId(), Account.ACTIVE);
        registrations.updateReview(
                registrationId, TenantRegistration.APPROVED, reviewer, "审批通过");
    }

    @Transactional
    public void reject(long registrationId, String reason, String reviewer) {
        TenantRegistration registration = require(registrationId);
        if (!TenantRegistration.PENDING.equals(registration.status())) {
            throw IamException.conflict("该申请已处理");
        }
        String note = requireText(reason, "拒绝原因", 500);
        tenants.updateStatus(registration.tenantId(), TenantStatus.REJECTED);
        accounts.updateStatus(registration.accountId(), Account.DISABLED);
        registrations.updateReview(registrationId, TenantRegistration.REJECTED, reviewer, note);
    }

    /** 把超过时限仍未处理的申请标记为过期，避免待办列表无限堆积。 */
    @Transactional
    public int expireStale() {
        String cutoff = clock.instant()
                .minus(properties.getRegistration().getPendingExpiry())
                .toString();
        int expired = registrations.expirePendingBefore(cutoff);
        if (expired > 0) {
            LOGGER.info("{} 条注册申请超时未处理，已标记过期", expired);
        }
        return expired;
    }

    private TenantRegistration require(long registrationId) {
        return registrations.findById(registrationId)
                .orElseThrow(() -> IamException.notFound("注册申请不存在"));
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw IamException.invalid("平台未开放自助注册，请联系管理员开通");
        }
    }

    /**
     * 租户编码由企业名生成并补随机后缀。名称可能全是中文，因此无法直接音译，
     * 退化为固定前缀 + 随机串，保证唯一即可——编码是内部标识，不面向终端用户。
     */
    private String generateTenantCode(String companyName) {
        StringBuilder prefix = new StringBuilder();
        for (char character : companyName.toCharArray()) {
            if (Character.isLetterOrDigit(character) && character < 128) {
                prefix.append(Character.toLowerCase(character));
            }
            if (prefix.length() >= 8) {
                break;
            }
        }
        String base = prefix.isEmpty() ? "tenant" : prefix.toString();
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = base + "-" + randomSuffix();
            if (!tenants.codeExists(candidate, null)) {
                return candidate;
            }
        }
        throw IamException.conflict("租户编码分配失败，请稍后重试");
    }

    private String randomSuffix() {
        return Long.toString(
                java.util.concurrent.ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36),
                36).toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw IamException.invalid(field + "不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw IamException.invalid(field + "最长 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    public record RegistrationRequest(
            String companyName,
            String contactName,
            String contactPhone,
            String username,
            String password,
            String captchaToken,
            String captchaCode) {}
}
