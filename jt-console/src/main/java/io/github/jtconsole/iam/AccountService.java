package io.github.jtconsole.iam;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Account;
import io.github.jtconsole.domain.AccountView;
import io.github.jtconsole.domain.Department;
import io.github.jtconsole.domain.Plan;
import io.github.jtconsole.domain.Position;
import io.github.jtconsole.domain.Role;
import io.github.jtconsole.domain.Tenant;
import io.github.jtconsole.repository.AccountRepository;
import io.github.jtconsole.repository.DepartmentRepository;
import io.github.jtconsole.repository.PlanRepository;
import io.github.jtconsole.repository.PositionRepository;
import io.github.jtconsole.repository.RoleRepository;
import io.github.jtconsole.repository.TenantRepository;
import io.github.jtconsole.security.AccountAuthenticationService;
import io.github.jtconsole.security.AuthorizationResolver;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.SessionTokenService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号全生命周期。
 *
 * <p>租户边界在这里是硬约束：租户用户无论持有什么权限码，都只能看见与修改本租户账号，
 * 也不能把任何账号绑定到平台级角色。
 */
@Service
public class AccountService {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_USERNAME_LENGTH = 64;
    private static final int MAX_DISPLAY_NAME_LENGTH = 100;

    private final AccountRepository accounts;
    private final RoleRepository roles;
    private final DepartmentRepository departments;
    private final PositionRepository positions;
    private final TenantRepository tenants;
    private final PlanRepository plans;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokenService sessions;
    private final AuthorizationResolver authorizations;
    private final AccountAuthenticationService authentication;

    public AccountService(
            AccountRepository accounts,
            RoleRepository roles,
            DepartmentRepository departments,
            PositionRepository positions,
            TenantRepository tenants,
            PlanRepository plans,
            PasswordEncoder passwordEncoder,
            SessionTokenService sessions,
            AuthorizationResolver authorizations,
            AccountAuthenticationService authentication) {
        this.accounts = accounts;
        this.roles = roles;
        this.departments = departments;
        this.positions = positions;
        this.tenants = tenants;
        this.plans = plans;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.authorizations = authorizations;
        this.authentication = authentication;
    }

    @Transactional(readOnly = true)
    public List<AccountView> search(AuthorizedPrincipal caller, Long tenantFilter, String keyword) {
        Long effectiveTenant = caller.platform() ? tenantFilter : caller.tenantId();
        return accounts.search(effectiveTenant, keyword).stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountView get(AuthorizedPrincipal caller, long accountId) {
        return toView(requireVisible(caller, accountId));
    }

    @Transactional
    public AccountView create(AuthorizedPrincipal caller, AccountRequest request) {
        String username = requireText(request.username(), "用户名", MAX_USERNAME_LENGTH);
        String password = request.password() == null ? "" : request.password();
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw IamException.invalid("密码至少 " + MIN_PASSWORD_LENGTH + " 个字符");
        }
        Long tenantId = resolveTargetTenant(caller, request.tenantId());
        if (accounts.usernameExists(username)) {
            throw IamException.conflict("用户名已被占用");
        }
        List<Long> roleIds = validateRoles(caller, tenantId, request.roleIds());
        validateOrganization(tenantId, request.departmentId(), request.positionId());
        if (tenantId != null) {
            enforceAccountQuota(tenantId);
        }

        String now = Timestamps.now();
        long accountId = accounts.insert(new Account(
                0L,
                username,
                passwordEncoder.encode(password),
                optionalText(request.displayName(), "显示名称", MAX_DISPLAY_NAME_LENGTH),
                tenantId,
                request.departmentId(),
                request.positionId(),
                Account.ACTIVE,
                null,
                now,
                now));
        roles.replaceAccountRoles(accountId, roleIds);
        authorizations.invalidate(accountId);
        return toView(accounts.findById(accountId).orElseThrow());
    }

    @Transactional
    public AccountView update(AuthorizedPrincipal caller, long accountId, AccountRequest request) {
        Account existing = requireVisible(caller, accountId);
        validateOrganization(existing.tenantId(), request.departmentId(), request.positionId());
        List<Long> roleIds = validateRoles(caller, existing.tenantId(), request.roleIds());
        if (isLastPlatformAdmin(existing) && !containsPlatformAdminRole(roleIds)) {
            throw IamException.conflict("必须至少保留一个启用的平台管理员");
        }

        accounts.updateProfile(
                accountId,
                optionalText(request.displayName(), "显示名称", MAX_DISPLAY_NAME_LENGTH),
                request.departmentId(),
                request.positionId());
        roles.replaceAccountRoles(accountId, roleIds);
        // 角色变更收紧权限时不能等 30 秒缓存自然过期。
        authorizations.invalidate(accountId);
        return toView(accounts.findById(accountId).orElseThrow());
    }

    @Transactional
    public void changeStatus(AuthorizedPrincipal caller, long accountId, boolean enabled) {
        Account existing = requireVisible(caller, accountId);
        if (!enabled && isLastPlatformAdmin(existing)) {
            throw IamException.conflict("必须至少保留一个启用的平台管理员");
        }
        accounts.updateStatus(accountId, enabled ? Account.ACTIVE : Account.DISABLED);
        authorizations.invalidate(accountId);
        if (!enabled) {
            // 禁用必须即时生效：访问 token 有效期 7 天，等它过期等于没禁用。
            sessions.revokeByAccount(accountId, null);
        }
    }

    @Transactional
    public void delete(AuthorizedPrincipal caller, long accountId) {
        Account existing = requireVisible(caller, accountId);
        if (existing.id() == caller.accountId()) {
            throw IamException.conflict("不能删除当前登录的账号");
        }
        if (isLastPlatformAdmin(existing)) {
            throw IamException.conflict("必须至少保留一个启用的平台管理员");
        }
        accounts.delete(accountId);
        authorizations.invalidate(accountId);
        sessions.revokeByAccount(accountId, null);
    }

    @Transactional
    public void resetPassword(AuthorizedPrincipal caller, long accountId, String newPassword) {
        requireVisible(caller, accountId);
        String password = newPassword == null ? "" : newPassword;
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw IamException.invalid("密码至少 " + MIN_PASSWORD_LENGTH + " 个字符");
        }
        accounts.updatePasswordHash(accountId, passwordEncoder.encode(password));
        authorizations.invalidate(accountId);
        // 管理员重置密码后目标账号的全部会话失效，必须以新密码重新登录。
        sessions.revokeByAccount(accountId, null);
    }

    /**
     * 用户修改自己的密码。保留当前会话，撤销该账号其他会话——
     * 改密的常见动机是「怀疑凭据泄露」，把别处的登录一起踢掉才有意义。
     */
    @Transactional
    public void changeOwnPassword(
            long accountId, String oldPassword, String newPassword, String currentSessionId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> IamException.notFound("账号不存在"));
        if (!authentication.passwordMatches(account, oldPassword)) {
            throw IamException.invalid("当前密码不正确");
        }
        String password = newPassword == null ? "" : newPassword;
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw IamException.invalid("新密码至少 " + MIN_PASSWORD_LENGTH + " 个字符");
        }
        accounts.updatePasswordHash(accountId, passwordEncoder.encode(password));
        authorizations.invalidate(accountId);
        sessions.revokeByAccount(accountId, currentSessionId);
    }

    /** 租户停用或删除时清理其账号会话。 */
    public void revokeTenantSessions(long tenantId) {
        sessions.revokeByTenant(tenantId);
        authorizations.invalidateTenant(tenantId);
    }

    @Transactional(readOnly = true)
    public int countByTenant(long tenantId) {
        return tenants.countAccounts(tenantId);
    }

    private void enforceAccountQuota(long tenantId) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> IamException.notFound("租户不存在"));
        if (tenant.planId() == null) {
            return;
        }
        Optional<Plan> plan = plans.findById(tenant.planId());
        if (plan.isEmpty()) {
            return;
        }
        int current = tenants.countAccounts(tenantId);
        if (plan.get().accountQuotaExceeded(current)) {
            throw IamException.quotaExceeded(
                    "账号数已达套餐上限 " + plan.get().maxAccounts() + "，请先升级套餐");
        }
    }

    /**
     * 目标租户：平台账号可指定任意租户（含平台侧的 null），租户用户只能是自己的租户。
     */
    private Long resolveTargetTenant(AuthorizedPrincipal caller, Long requested) {
        if (!caller.platform()) {
            return caller.tenantId();
        }
        if (requested == null) {
            return null;
        }
        Tenant tenant = tenants.findById(requested)
                .orElseThrow(() -> IamException.notFound("租户不存在"));
        if (!authentication.tenantActive(tenant)) {
            throw IamException.invalid("目标租户已停用或已到期，不能创建账号");
        }
        return tenant.id();
    }

    private List<Long> validateRoles(AuthorizedPrincipal caller, Long tenantId, List<Long> requested) {
        List<Long> roleIds = requested == null ? List.of() : List.copyOf(new LinkedHashSet<>(requested));
        if (roleIds.isEmpty()) {
            throw IamException.invalid("至少需要绑定一个角色");
        }
        List<Long> validated = new ArrayList<>(roleIds.size());
        for (Long roleId : roleIds) {
            Role role = roles.findById(roleId)
                    .orElseThrow(() -> IamException.notFound("角色不存在"));
            boolean platformAdminRole = role.platformRole()
                    && Role.PLATFORM_ADMIN.equals(role.code());
            if (platformAdminRole) {
                if (!caller.platform()) {
                    throw IamException.notFound("角色不存在");
                }
                if (tenantId != null) {
                    throw IamException.invalid("平台管理员角色不能绑定到租户账号");
                }
            }
            if (!role.platformRole() && !role.tenantId().equals(tenantId)) {
                throw IamException.notFound("角色不存在");
            }
            if (role.platformRole() && !platformAdminRole && tenantId == null) {
                throw IamException.invalid("平台账号只能绑定平台管理员角色");
            }
            validated.add(role.id());
        }
        return validated;
    }

    private void validateOrganization(Long tenantId, Long departmentId, Long positionId) {
        if (departmentId != null) {
            Department department = departments.findById(departmentId)
                    .orElseThrow(() -> IamException.notFound("部门不存在"));
            if (tenantId == null || department.tenantId() != tenantId) {
                throw IamException.notFound("部门不存在");
            }
        }
        if (positionId != null) {
            Position position = positions.findById(positionId)
                    .orElseThrow(() -> IamException.notFound("岗位不存在"));
            if (tenantId == null || position.tenantId() != tenantId) {
                throw IamException.notFound("岗位不存在");
            }
        }
    }

    /**
     * 越权访问一律按「不存在」处理：403 会告诉租户「这个账号存在，只是不属于你」。
     */
    private Account requireVisible(AuthorizedPrincipal caller, long accountId) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> IamException.notFound("账号不存在"));
        if (caller.platform()) {
            return account;
        }
        if (account.tenantId() == null || !account.tenantId().equals(caller.tenantId())) {
            throw IamException.notFound("账号不存在");
        }
        return account;
    }

    private boolean isLastPlatformAdmin(Account account) {
        if (!account.platformAccount() || !account.enabled()) {
            return false;
        }
        boolean hasPlatformAdminRole = roles.findByAccount(account.id()).stream()
                .anyMatch(role -> role.platformRole() && Role.PLATFORM_ADMIN.equals(role.code()));
        return hasPlatformAdminRole && accounts.countActivePlatformAdmins(account.id()) == 0;
    }

    private boolean containsPlatformAdminRole(List<Long> roleIds) {
        return roleIds.stream()
                .map(roles::findById)
                .flatMap(Optional::stream)
                .anyMatch(role -> role.platformRole() && Role.PLATFORM_ADMIN.equals(role.code()));
    }

    private AccountView toView(Account account) {
        String tenantName = account.tenantId() == null
                ? null
                : tenants.findById(account.tenantId()).map(Tenant::name).orElse(null);
        String departmentName = account.departmentId() == null
                ? null
                : departments.findById(account.departmentId()).map(Department::name).orElse(null);
        String positionName = account.positionId() == null
                ? null
                : positions.findById(account.positionId()).map(Position::name).orElse(null);
        List<Role.Summary> boundRoles = roles.findByAccount(account.id()).stream()
                .map(Role::summary)
                .toList();
        return new AccountView(
                account.id(), account.username(), account.displayName(),
                account.tenantId(), tenantName,
                account.departmentId(), departmentName,
                account.positionId(), positionName,
                account.status(), account.lastLoginAt(),
                account.createdAt(), account.updatedAt(), boundRoles);
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

    private static String optionalText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > maxLength) {
            throw IamException.invalid(field + "最长 " + maxLength + " 个字符");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 账号写入请求。 */
    public record AccountRequest(
            String username,
            String password,
            String displayName,
            Long tenantId,
            Long departmentId,
            Long positionId,
            List<Long> roleIds) {

        public AccountRequest {
            roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
        }
    }
}
