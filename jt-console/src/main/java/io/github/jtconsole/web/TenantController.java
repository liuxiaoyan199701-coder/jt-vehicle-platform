package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.TenantOrder;
import io.github.jtconsole.iam.PlanService;
import io.github.jtconsole.iam.TenantService;
import io.github.jtconsole.iam.TenantService.TenantRequest;
import io.github.jtconsole.iam.TenantService.TenantView;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理。整个控制器只对平台管理员开放——租户不该知道平台上还有别的租户。
 */
@RestController
@RequestMapping("/api/platform/tenants")
@RequirePermission(Permissions.PLATFORM_TENANT_MANAGE)
public class TenantController {

    private final TenantService tenants;
    private final PlanService plans;

    public TenantController(TenantService tenants, PlanService plans) {
        this.tenants = tenants;
        this.plans = plans;
    }

    @GetMapping
    public ApiResponse<List<TenantView>> list() {
        return ApiResponse.ok(tenants.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<TenantView> get(@PathVariable long id) {
        return ApiResponse.ok(tenants.get(id));
    }

    @PostMapping
    @Audited(value = "新增租户", resourceType = "tenant")
    public ApiResponse<TenantView> create(@RequestBody TenantRequest request) {
        return ApiResponse.ok(tenants.create(request));
    }

    @PutMapping("/{id}")
    @Audited(value = "编辑租户", resourceType = "tenant")
    public ApiResponse<TenantView> update(
            @PathVariable long id, @RequestBody TenantRequest request) {
        return ApiResponse.ok(tenants.update(id, request));
    }

    @PutMapping("/{id}/status")
    @Audited(value = "启停租户", resourceType = "tenant")
    public ApiResponse<TenantView> changeStatus(
            @PathVariable long id, @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body == null ? null : body.get("enabled");
        if (enabled == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        return ApiResponse.ok(tenants.changeStatus(id, enabled));
    }

    @DeleteMapping("/{id}")
    @Audited(value = "删除租户", resourceType = "tenant")
    public ApiResponse<Void> delete(@PathVariable long id) {
        tenants.delete(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/orders")
    public ApiResponse<List<TenantOrder>> orders(@PathVariable long id) {
        return ApiResponse.ok(plans.ordersOf(id));
    }

    @PostMapping("/{id}/renew")
    @Audited(value = "录入续费", resourceType = "tenant")
    public ApiResponse<TenantOrder> renew(
            @PathVariable long id,
            @RequestBody RenewBody body,
            AuthorizedPrincipal principal) {
        if (body == null) {
            throw new IllegalArgumentException("续费信息不能为空");
        }
        return ApiResponse.ok(plans.renew(
                new PlanService.RenewRequest(
                        id, body.planId(), body.months(), body.amountCents(), body.remark()),
                principal.username()));
    }

    /** {@code months} 允许为负，用于红冲纠错。 */
    public record RenewBody(Long planId, int months, long amountCents, String remark) {}
}
