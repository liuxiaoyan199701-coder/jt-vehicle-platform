package io.github.jtconsole.iam;

import io.github.jtconsole.domain.Department;
import io.github.jtconsole.domain.Position;
import io.github.jtconsole.repository.AccountRepository;
import io.github.jtconsole.repository.DepartmentRepository;
import io.github.jtconsole.repository.PositionRepository;
import io.github.jtconsole.security.AuthorizationResolver;
import io.github.jtconsole.security.AuthorizedPrincipal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 部门树与岗位字典。
 *
 * <p>岗位刻意不参与任何权限或数据范围判定：一旦岗位也能授权，它就变成了第二套角色模型，
 * 之后每个授权问题都要同时查两处。
 */
@Service
public class OrganizationService {

    private static final int MAX_DEPARTMENT_NAME = 100;
    private static final int MAX_POSITION_NAME = 50;
    private static final int MAX_REMARK = 200;
    /** 部门树深度上限。规格要求至少支持 5 层，这里留出余量并挡住病态深树。 */
    private static final int MAX_DEPTH = 10;

    private final DepartmentRepository departments;
    private final PositionRepository positions;
    private final AccountRepository accounts;
    private final AuthorizationResolver authorizations;

    public OrganizationService(
            DepartmentRepository departments,
            PositionRepository positions,
            AccountRepository accounts,
            AuthorizationResolver authorizations) {
        this.departments = departments;
        this.positions = positions;
        this.accounts = accounts;
        this.authorizations = authorizations;
    }

    @Transactional(readOnly = true)
    public List<Department.Node> tree(AuthorizedPrincipal caller, Long tenantFilter) {
        long tenantId = requireTenant(caller, tenantFilter);
        List<Department> all = departments.findByTenant(tenantId);
        Map<Long, int[]> usage = departments.countUsage(tenantId);

        Map<Long, List<Department>> childrenByParent = new LinkedHashMap<>();
        for (Department department : all) {
            childrenByParent
                    .computeIfAbsent(department.parentId(), key -> new ArrayList<>())
                    .add(department);
        }
        return buildNodes(childrenByParent, usage, null);
    }

    @Transactional
    public Department createDepartment(AuthorizedPrincipal caller, DepartmentRequest request) {
        long tenantId = requireTenant(caller, request.tenantId());
        String name = requireText(request.name(), "部门名称", MAX_DEPARTMENT_NAME);
        Long parentId = validateParent(tenantId, request.parentId(), null);
        if (departments.nameExistsUnderParent(tenantId, parentId, name, null)) {
            throw IamException.conflict("同级下已存在同名部门");
        }
        String now = Instant.now().toString();
        long id = departments.insert(new Department(
                0L, tenantId, parentId, name, request.order(), request.active(), now, now));
        authorizations.invalidateTenant(tenantId);
        return departments.findById(id).orElseThrow();
    }

    @Transactional
    public Department updateDepartment(
            AuthorizedPrincipal caller, long departmentId, DepartmentRequest request) {
        Department existing = requireVisibleDepartment(caller, departmentId);
        String name = requireText(request.name(), "部门名称", MAX_DEPARTMENT_NAME);
        Long parentId = validateParent(existing.tenantId(), request.parentId(), departmentId);
        if (departments.nameExistsUnderParent(
                existing.tenantId(), parentId, name, departmentId)) {
            throw IamException.conflict("同级下已存在同名部门");
        }
        departments.update(departmentId, parentId, name, request.order(), request.active());
        // 移动部门会改变各账号可见的子树，必须让该租户的数据范围重新解析。
        authorizations.invalidateTenant(existing.tenantId());
        return departments.findById(departmentId).orElseThrow();
    }

    @Transactional
    public void deleteDepartment(AuthorizedPrincipal caller, long departmentId) {
        Department existing = requireVisibleDepartment(caller, departmentId);
        if (departments.countChildren(departmentId) > 0) {
            throw IamException.conflict("该部门仍有子部门，请先迁移或删除子部门");
        }
        if (accounts.countByDepartment(departmentId) > 0) {
            throw IamException.conflict("该部门仍有账号，请先调整账号归属");
        }
        if (departments.countVehicles(departmentId) > 0) {
            throw IamException.conflict("该部门仍有车辆，请先调整车辆归属");
        }
        departments.delete(departmentId);
        authorizations.invalidateTenant(existing.tenantId());
    }

    @Transactional(readOnly = true)
    public List<Position> positions(AuthorizedPrincipal caller, Long tenantFilter) {
        return positions.findByTenant(requireTenant(caller, tenantFilter));
    }

    @Transactional
    public Position createPosition(AuthorizedPrincipal caller, PositionRequest request) {
        long tenantId = requireTenant(caller, request.tenantId());
        String name = requireText(request.name(), "岗位名称", MAX_POSITION_NAME);
        if (positions.nameExists(tenantId, name, null)) {
            throw IamException.conflict("岗位名称已存在");
        }
        String now = Instant.now().toString();
        long id = positions.insert(new Position(
                0L, tenantId, name, request.order(),
                optionalText(request.remark(), "备注", MAX_REMARK), now, now));
        return positions.findById(id).orElseThrow();
    }

    @Transactional
    public Position updatePosition(
            AuthorizedPrincipal caller, long positionId, PositionRequest request) {
        Position existing = requireVisiblePosition(caller, positionId);
        String name = requireText(request.name(), "岗位名称", MAX_POSITION_NAME);
        if (positions.nameExists(existing.tenantId(), name, positionId)) {
            throw IamException.conflict("岗位名称已存在");
        }
        positions.update(positionId, name, request.order(),
                optionalText(request.remark(), "备注", MAX_REMARK));
        return positions.findById(positionId).orElseThrow();
    }

    @Transactional
    public void deletePosition(AuthorizedPrincipal caller, long positionId) {
        requireVisiblePosition(caller, positionId);
        if (accounts.countByPosition(positionId) > 0) {
            throw IamException.conflict("该岗位仍被账号引用，请先调整相关账号");
        }
        positions.delete(positionId);
    }

    /** 校验部门属于指定租户，供车辆归属等外部调用复用。 */
    @Transactional(readOnly = true)
    public void requireDepartmentInTenant(Long departmentId, Long tenantId) {
        if (departmentId == null) {
            return;
        }
        Department department = departments.findById(departmentId)
                .orElseThrow(() -> IamException.notFound("部门不存在"));
        if (tenantId == null || department.tenantId() != tenantId) {
            throw IamException.notFound("部门不存在");
        }
    }

    private List<Department.Node> buildNodes(
            Map<Long, List<Department>> childrenByParent,
            Map<Long, int[]> usage,
            Long parentId) {
        return childrenByParent.getOrDefault(parentId, List.of()).stream()
                .map(department -> {
                    int[] counts = usage.getOrDefault(department.id(), new int[2]);
                    return new Department.Node(
                            department.id(), department.parentId(), department.name(),
                            department.sortOrder(), department.enabled(),
                            counts[0], counts[1],
                            buildNodes(childrenByParent, usage, department.id()));
                })
                .toList();
    }

    /**
     * 父部门必须同租户；把某部门的父级设为其自身或其子孙会形成环，
     * 环一旦形成，子树展开就会无限递归，数据范围解析随之失效。
     */
    private Long validateParent(long tenantId, Long parentId, Long movingDepartmentId) {
        if (parentId == null) {
            return null;
        }
        Department parent = departments.findById(parentId)
                .orElseThrow(() -> IamException.notFound("上级部门不存在"));
        if (parent.tenantId() != tenantId) {
            throw IamException.notFound("上级部门不存在");
        }
        if (movingDepartmentId != null) {
            Set<Long> subtree = departments.findSubtreeIds(tenantId, movingDepartmentId);
            if (subtree.contains(parentId)) {
                throw IamException.invalid("上级部门不能是自身或其下级部门");
            }
        }
        if (depthOf(tenantId, parentId) + 1 >= MAX_DEPTH) {
            throw IamException.invalid("部门层级不能超过 " + MAX_DEPTH + " 层");
        }
        return parentId;
    }

    private int depthOf(long tenantId, long departmentId) {
        Map<Long, Long> parents = new LinkedHashMap<>();
        for (Department department : departments.findByTenant(tenantId)) {
            parents.put(department.id(), department.parentId());
        }
        int depth = 0;
        Long current = departmentId;
        while (current != null && depth < MAX_DEPTH + 1) {
            current = parents.get(current);
            depth++;
        }
        return depth;
    }

    private long requireTenant(AuthorizedPrincipal caller, Long tenantFilter) {
        Long tenantId = caller.platform() ? tenantFilter : caller.tenantId();
        if (tenantId == null) {
            throw IamException.invalid("请先选择租户");
        }
        return tenantId;
    }

    private Department requireVisibleDepartment(AuthorizedPrincipal caller, long departmentId) {
        Department department = departments.findById(departmentId)
                .orElseThrow(() -> IamException.notFound("部门不存在"));
        if (!caller.platform()
                && (caller.tenantId() == null || department.tenantId() != caller.tenantId())) {
            throw IamException.notFound("部门不存在");
        }
        return department;
    }

    private Position requireVisiblePosition(AuthorizedPrincipal caller, long positionId) {
        Position position = positions.findById(positionId)
                .orElseThrow(() -> IamException.notFound("岗位不存在"));
        if (!caller.platform()
                && (caller.tenantId() == null || position.tenantId() != caller.tenantId())) {
            throw IamException.notFound("岗位不存在");
        }
        return position;
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

    /** 可选字段用包装类型：缺省的 JSON 字段是 null，映射到基本类型会让请求整体解析失败。 */
    public record DepartmentRequest(
            Long tenantId, Long parentId, String name, Integer sortOrder, Boolean enabled) {

        int order() {
            return sortOrder == null ? 0 : sortOrder;
        }

        boolean active() {
            return enabled == null || enabled;
        }
    }

    public record PositionRequest(
            Long tenantId, String name, Integer sortOrder, String remark) {

        int order() {
            return sortOrder == null ? 0 : sortOrder;
        }
    }
}
