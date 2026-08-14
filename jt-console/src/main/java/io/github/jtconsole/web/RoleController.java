package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.PermissionDefinition;
import io.github.jtconsole.domain.Role;
import io.github.jtconsole.iam.RoleService;
import io.github.jtconsole.iam.RoleService.RoleRequest;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/roles")
public class RoleController {

    private final RoleService roles;

    public RoleController(RoleService roles) {
        this.roles = roles;
    }

    /** 可勾选的权限点目录。租户看不到平台级权限码，因此也无从越级授权。 */
    @GetMapping("/permissions")
    @RequirePermission(Permissions.SYSTEM_ROLE_LIST)
    public ApiResponse<List<PermissionDefinition>> permissions(AuthorizedPrincipal principal) {
        return ApiResponse.ok(roles.catalog(principal));
    }

    @GetMapping
    @RequirePermission(Permissions.SYSTEM_ROLE_LIST)
    public ApiResponse<List<Role.Details>> list(
            @RequestParam(required = false) Long tenantId, AuthorizedPrincipal principal) {
        return ApiResponse.ok(roles.list(principal, tenantId));
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.SYSTEM_ROLE_LIST)
    public ApiResponse<Role.Details> get(@PathVariable long id, AuthorizedPrincipal principal) {
        return ApiResponse.ok(roles.get(principal, id));
    }

    @PostMapping
    @RequirePermission(Permissions.SYSTEM_ROLE_MANAGE)
    @Audited(value = "新增角色", resourceType = "role")
    public ApiResponse<Role.Details> create(
            @RequestBody RoleRequest request, AuthorizedPrincipal principal) {
        return ApiResponse.ok(roles.create(principal, request));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SYSTEM_ROLE_MANAGE)
    @Audited(value = "编辑角色", resourceType = "role")
    public ApiResponse<Role.Details> update(
            @PathVariable long id, @RequestBody RoleRequest request,
            AuthorizedPrincipal principal) {
        return ApiResponse.ok(roles.update(principal, id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SYSTEM_ROLE_MANAGE)
    @Audited(value = "删除角色", resourceType = "role")
    public ApiResponse<Void> delete(@PathVariable long id, AuthorizedPrincipal principal) {
        roles.delete(principal, id);
        return ApiResponse.ok(null);
    }
}
