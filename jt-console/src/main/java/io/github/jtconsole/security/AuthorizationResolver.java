package io.github.jtconsole.security;

import io.github.jtconsole.domain.Account;
import io.github.jtconsole.domain.DataScopeType;
import io.github.jtconsole.domain.Role;
import io.github.jtconsole.repository.AccountRepository;
import io.github.jtconsole.repository.DepartmentRepository;
import io.github.jtconsole.repository.RoleRepository;
import io.github.jtconsole.repository.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 把「账号 → 多角色 → 权限码并集 + 数据范围」解析成一次请求可用的授权上下文。
 *
 * <p>解析结果带 30 秒 TTL 的进程内缓存：权限解析要连查三张表，广播与高频接口承受不起裸查库；
 * 而把权限写进会话快照又意味着调整角色要等 7 天的访问 token 过期才生效。
 * 收紧类操作（禁用账号、停用租户、重置密码）走即时失效 + 会话撤销，不受这 30 秒影响。
 */
@Service
public class AuthorizationResolver {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final AccountRepository accounts;
    private final RoleRepository roles;
    private final DepartmentRepository departments;
    private final TenantRepository tenants;
    private final Clock clock;
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public AuthorizationResolver(
            AccountRepository accounts,
            RoleRepository roles,
            DepartmentRepository departments,
            TenantRepository tenants) {
        this(accounts, roles, departments, tenants, Clock.systemUTC());
    }

    AuthorizationResolver(
            AccountRepository accounts,
            RoleRepository roles,
            DepartmentRepository departments,
            TenantRepository tenants,
            Clock clock) {
        this.accounts = accounts;
        this.roles = roles;
        this.departments = departments;
        this.tenants = tenants;
        this.clock = clock;
    }

    /**
     * 解析账号的授权上下文；账号已不存在或被禁用时返回空，调用方应据此拒绝请求。
     */
    public Optional<AuthorizedPrincipal> resolve(long accountId) {
        Instant now = clock.instant();
        CacheEntry cached = cache.get(accountId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return Optional.ofNullable(cached.principal());
        }
        AuthorizedPrincipal resolved = load(accountId).orElse(null);
        cache.put(accountId, new CacheEntry(resolved, now.plus(CACHE_TTL)));
        return Optional.ofNullable(resolved);
    }

    public void invalidate(long accountId) {
        cache.remove(accountId);
    }

    /** 租户状态或组织结构变化时失效该租户全部账号的解析结果。 */
    public void invalidateTenant(long tenantId) {
        for (Long accountId : accounts.findIdsByTenant(tenantId)) {
            cache.remove(accountId);
        }
    }

    /** 角色或权限目录变化时全量失效——影响面无法按账号精确计算，且缓存重建成本很低。 */
    public void invalidateAll() {
        cache.clear();
    }

    private Optional<AuthorizedPrincipal> load(long accountId) {
        Optional<Account> found = accounts.findById(accountId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Account account = found.get();
        if (!account.enabled()) {
            return Optional.empty();
        }

        List<Role> boundRoles = roles.findByAccount(accountId);
        Set<String> permissions = Set.copyOf(roles.findPermissionCodesForAccount(accountId));
        boolean platform = account.platformAccount();
        String tenantName = account.tenantId() == null
                ? null
                : tenants.findById(account.tenantId()).map(tenant -> tenant.name()).orElse(null);

        DataScope scope = platform
                ? DataScope.platform()
                : resolveTenantScope(account, boundRoles);

        return Optional.of(new AuthorizedPrincipal(
                account.id(),
                account.username(),
                account.displayName(),
                account.tenantId(),
                tenantName,
                platform,
                permissions,
                boundRoles.stream().map(Role::summary).toList(),
                scope));
    }

    /**
     * 多角色取「最宽」：任一角色是租户全量即租户全量，否则取各角色可见部门集合的并集。
     *
     * <p>不用枚举序号比大小——自定义部门集合与「本部门及以下」谁更宽取决于实际数据，
     * 并集才是「最宽」的准确含义。
     */
    private DataScope resolveTenantScope(Account account, List<Role> boundRoles) {
        long tenantId = account.tenantId();
        if (boundRoles.isEmpty()) {
            return DataScope.departments(tenantId, Set.of());
        }

        Set<Long> visible = new LinkedHashSet<>();
        for (Role role : boundRoles) {
            DataScopeType type = role.dataScopeValue();
            if (type == DataScopeType.TENANT) {
                return DataScope.tenantWide(tenantId);
            }
            visible.addAll(departmentsFor(account, role, type));
        }
        return DataScope.departments(tenantId, visible);
    }

    private Set<Long> departmentsFor(Account account, Role role, DataScopeType type) {
        long tenantId = account.tenantId();
        return switch (type) {
            case TENANT -> Set.of();
            case DEPT -> account.departmentId() == null
                    ? Set.of()
                    : Set.of(account.departmentId());
            case DEPT_AND_CHILDREN -> account.departmentId() == null
                    ? Set.of()
                    : departments.findSubtreeIds(tenantId, account.departmentId());
            case CUSTOM -> {
                Set<Long> configured = Set.copyOf(roles.findDepartments(role.id()));
                // 自定义范围同样包含所选部门的子孙，否则新建子部门会在角色配置外静默失明。
                yield configured.isEmpty()
                        ? Set.of()
                        : departments.findSubtreeIds(tenantId, configured);
            }
        };
    }

    private record CacheEntry(AuthorizedPrincipal principal, Instant expiresAt) {}
}
