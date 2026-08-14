package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.Department;
import io.github.jtconsole.domain.Position;
import io.github.jtconsole.iam.OrganizationService;
import io.github.jtconsole.iam.OrganizationService.DepartmentRequest;
import io.github.jtconsole.iam.OrganizationService.PositionRequest;
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
@RequestMapping("/api/system")
public class OrganizationController {

    private final OrganizationService organization;

    public OrganizationController(OrganizationService organization) {
        this.organization = organization;
    }

    @GetMapping("/departments")
    @RequirePermission(Permissions.SYSTEM_DEPT_LIST)
    public ApiResponse<List<Department.Node>> tree(
            @RequestParam(required = false) Long tenantId, AuthorizedPrincipal principal) {
        return ApiResponse.ok(organization.tree(principal, tenantId));
    }

    @PostMapping("/departments")
    @RequirePermission(Permissions.SYSTEM_DEPT_MANAGE)
    @Audited(value = "新增部门", resourceType = "department")
    public ApiResponse<Department> createDepartment(
            @RequestBody DepartmentRequest request, AuthorizedPrincipal principal) {
        return ApiResponse.ok(organization.createDepartment(principal, request));
    }

    @PutMapping("/departments/{id}")
    @RequirePermission(Permissions.SYSTEM_DEPT_MANAGE)
    @Audited(value = "编辑部门", resourceType = "department")
    public ApiResponse<Department> updateDepartment(
            @PathVariable long id, @RequestBody DepartmentRequest request,
            AuthorizedPrincipal principal) {
        return ApiResponse.ok(organization.updateDepartment(principal, id, request));
    }

    @DeleteMapping("/departments/{id}")
    @RequirePermission(Permissions.SYSTEM_DEPT_MANAGE)
    @Audited(value = "删除部门", resourceType = "department")
    public ApiResponse<Void> deleteDepartment(
            @PathVariable long id, AuthorizedPrincipal principal) {
        organization.deleteDepartment(principal, id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/positions")
    @RequirePermission(Permissions.SYSTEM_POSITION_LIST)
    public ApiResponse<List<Position>> positions(
            @RequestParam(required = false) Long tenantId, AuthorizedPrincipal principal) {
        return ApiResponse.ok(organization.positions(principal, tenantId));
    }

    @PostMapping("/positions")
    @RequirePermission(Permissions.SYSTEM_POSITION_MANAGE)
    @Audited(value = "新增岗位", resourceType = "position")
    public ApiResponse<Position> createPosition(
            @RequestBody PositionRequest request, AuthorizedPrincipal principal) {
        return ApiResponse.ok(organization.createPosition(principal, request));
    }

    @PutMapping("/positions/{id}")
    @RequirePermission(Permissions.SYSTEM_POSITION_MANAGE)
    @Audited(value = "编辑岗位", resourceType = "position")
    public ApiResponse<Position> updatePosition(
            @PathVariable long id, @RequestBody PositionRequest request,
            AuthorizedPrincipal principal) {
        return ApiResponse.ok(organization.updatePosition(principal, id, request));
    }

    @DeleteMapping("/positions/{id}")
    @RequirePermission(Permissions.SYSTEM_POSITION_MANAGE)
    @Audited(value = "删除岗位", resourceType = "position")
    public ApiResponse<Void> deletePosition(@PathVariable long id, AuthorizedPrincipal principal) {
        organization.deletePosition(principal, id);
        return ApiResponse.ok(null);
    }
}
