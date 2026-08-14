package io.github.jtconsole.iam;

import io.github.jtconsole.domain.DataScopeType;
import io.github.jtconsole.domain.Department;
import io.github.jtconsole.domain.PermissionDefinition;
import io.github.jtconsole.domain.Role;
import io.github.jtconsole.repository.DepartmentRepository;
import io.github.jtconsole.repository.RoleRepository;
import io.github.jtconsole.security.AuthorizationResolver;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理。
 *
 * <p>三条不可越过的线：内置角色的权限集合由代码同步、界面不可改；租户自定义角色不得包含平台级
 * 权限码（否则租户可以自己授权自己管理别的租户）；角色配置里的权限码必须存在于代码目录，
 * 伪造的权限码整体拒绝而不是静默丢弃。
 */
@Service
public class RoleService {

    private static final int MAX_CODE_LENGTH = 32;
    private static final int MAX_NAME_LENGTH = 50;
    private static final int MAX_REMARK_LENGTH = 200;

    private final RoleRepository roles;
    private final DepartmentRepository departments;
    private final AuthorizationResolver authorizations;

    public RoleService(
            RoleRepository roles,
            DepartmentRepository departments,
            AuthorizationResolver authorizations) {
        this.roles = roles;
        this.departments = departments;
        this.authorizations = authorizations;
    }

    /** 权限码目录取自代码，不查数据库：代码才是权限点的唯一权威。 */
    public List<PermissionDefinition> catalog(AuthorizedPrincipal caller) {
        return Permissions.catalog().stream()
                .filter(definition -> caller.platform() || !definition.platformOnly())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Role.Details> list(AuthorizedPrincipal caller, Long tenantFilter) {
        Long tenantId = caller.platform() ? tenantFilter : caller.tenantId();
        List<Role> visible = caller.platform() && tenantId == null
                ? allRoles()
                : roles.findAvailableFor(tenantId);
        return visible.stream().map(this::toDetails).toList();
    }

    @Transactional(readOnly = true)
    public Role.Details get(AuthorizedPrincipal caller, long roleId) {
        return toDetails(requireVisible(caller, roleId));
    }

    @Transactional
    public Role.Details create(AuthorizedPrincipal caller, RoleRequest request) {
        Long tenantId = resolveTargetTenant(caller, request.tenantId());
        if (tenantId == null) {
            throw IamException.invalid("平台内置角色由系统维护，不支持新建");
        }
        String code = requireText(request.code(), "角色编码", MAX_CODE_LENGTH)
                .toUpperCase(Locale.ROOT);
        if (roles.codeExists(tenantId, code, null)) {
            throw IamException.conflict("角色编码已被占用");
        }
        List<String> permissions = validatePermissions(request.permissions());
        DataScopeType scope = DataScopeType.of(request.dataScope());
        List<Long> departmentIds = validateDepartments(tenantId, scope, request.departmentIds());

        String now = Instant.now().toString();
        long roleId = roles.insert(new Role(
                0L, tenantId, code,
                requireText(request.name(), "角色名称", MAX_NAME_LENGTH),
                false, scope.name(),
                optionalText(request.remark(), "备注", MAX_REMARK_LENGTH),
                now, now));
        roles.replacePermissions(roleId, permissions);
        roles.replaceDepartments(roleId, departmentIds);
        authorizations.invalidateAll();
        return toDetails(roles.findById(roleId).orElseThrow());
    }

    @Transactional
    public Role.Details update(AuthorizedPrincipal caller, long roleId, RoleRequest request) {
        Role existing = requireVisible(caller, roleId);
        if (existing.builtin()) {
            throw IamException.conflict("内置角色的权限由系统维护，不能修改");
        }
        List<String> permissions = validatePermissions(request.permissions());
        DataScopeType scope = DataScopeType.of(request.dataScope());
        List<Long> departmentIds =
                validateDepartments(existing.tenantId(), scope, request.departmentIds());

        roles.update(new Role(
                existing.id(), existing.tenantId(), existing.code(),
                requireText(request.name(), "角色名称", MAX_NAME_LENGTH),
                false, scope.name(),
                optionalText(request.remark(), "备注", MAX_REMARK_LENGTH),
                existing.createdAt(), Instant.now().toString()));
        roles.replacePermissions(roleId, permissions);
        roles.replaceDepartments(roleId, departmentIds);
        // 收紧授权必须尽快生效，不能等各账号的 30 秒缓存自然过期。
        authorizations.invalidateAll();
        return toDetails(roles.findById(roleId).orElseThrow());
    }

    @Transactional
    public void delete(AuthorizedPrincipal caller, long roleId) {
        Role existing = requireVisible(caller, roleId);
        if (existing.builtin()) {
            throw IamException.conflict("内置角色不能删除");
        }
        if (roles.countAccounts(roleId) > 0) {
            throw IamException.conflict("该角色仍被账号绑定，请先调整相关账号");
        }
        roles.delete(roleId);
        authorizations.invalidateAll();
    }

    private List<Role> allRoles() {
        List<Role> combined = new ArrayList<>(roles.findByTenant(null));
        combined.addAll(roles.findAllTenantRoles());
        return combined;
    }

    private Long resolveTargetTenant(AuthorizedPrincipal caller, Long requested) {
        return caller.platform() ? requested : caller.tenantId();
    }

    /**
     * 权限码必须存在于代码目录；租户角色额外不得含平台级权限码，
     * 否则租户管理员可以给自己授权管理别的租户。
     */
    private List<String> validatePermissions(List<String> requested) {
        List<String> codes = requested == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(requested));
        if (codes.isEmpty()) {
            throw IamException.invalid("至少需要勾选一个权限");
        }
        for (String code : codes) {
            if (!Permissions.exists(code)) {
                throw IamException.invalid("权限码不存在：" + code);
            }
            if (Permissions.isPlatformOnly(code)) {
                throw IamException.invalid("租户角色不能包含平台级权限：" + code);
            }
        }
        return codes;
    }

    private List<Long> validateDepartments(
            Long tenantId, DataScopeType scope, List<Long> requested) {
        if (scope != DataScopeType.CUSTOM) {
            return List.of();
        }
        List<Long> ids = requested == null ? List.of() : List.copyOf(new LinkedHashSet<>(requested));
        if (ids.isEmpty()) {
            throw IamException.invalid("自定义数据范围至少需要选择一个部门");
        }
        for (Long departmentId : ids) {
            Department department = departments.findById(departmentId)
                    .orElseThrow(() -> IamException.notFound("部门不存在"));
            if (tenantId == null || department.tenantId() != tenantId) {
                throw IamException.notFound("部门不存在");
            }
        }
        return ids;
    }

    private Role requireVisible(AuthorizedPrincipal caller, long roleId) {
        Role role = roles.findById(roleId)
                .orElseThrow(() -> IamException.notFound("角色不存在"));
        if (caller.platform()) {
            return role;
        }
        boolean tenantTemplate = role.platformRole() && !Role.PLATFORM_ADMIN.equals(role.code());
        boolean ownRole = !role.platformRole() && role.tenantId().equals(caller.tenantId());
        if (!tenantTemplate && !ownRole) {
            throw IamException.notFound("角色不存在");
        }
        return role;
    }

    private Role.Details toDetails(Role role) {
        return new Role.Details(
                role,
                roles.findPermissions(role.id()),
                roles.findDepartments(role.id()),
                roles.countAccounts(role.id()));
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

    /** 角色写入请求。 */
    public record RoleRequest(
            Long tenantId,
            String code,
            String name,
            String dataScope,
            String remark,
            List<String> permissions,
            List<Long> departmentIds) {

        public RoleRequest {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
            departmentIds = departmentIds == null ? List.of() : List.copyOf(departmentIds);
        }
    }
}
