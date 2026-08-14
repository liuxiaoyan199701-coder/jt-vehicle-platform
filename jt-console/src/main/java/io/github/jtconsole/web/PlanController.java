package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.Plan;
import io.github.jtconsole.domain.TenantOrder;
import io.github.jtconsole.iam.PlanService;
import io.github.jtconsole.iam.PlanService.PlanRequest;
import io.github.jtconsole.iam.PlanService.PlanView;
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
@RequestMapping("/api/platform/plans")
@RequirePermission(Permissions.PLATFORM_PLAN_MANAGE)
public class PlanController {

    private final PlanService plans;

    public PlanController(PlanService plans) {
        this.plans = plans;
    }

    @GetMapping
    public ApiResponse<List<PlanView>> list() {
        return ApiResponse.ok(plans.list());
    }

    @PostMapping
    @Audited(value = "新增套餐", resourceType = "plan")
    public ApiResponse<Plan> create(@RequestBody PlanRequest request) {
        return ApiResponse.ok(plans.create(request));
    }

    @PutMapping("/{id}")
    @Audited(value = "编辑套餐", resourceType = "plan")
    public ApiResponse<Plan> update(@PathVariable long id, @RequestBody PlanRequest request) {
        return ApiResponse.ok(plans.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Audited(value = "删除套餐", resourceType = "plan")
    public ApiResponse<Void> delete(@PathVariable long id) {
        plans.delete(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/orders")
    public ApiResponse<List<TenantOrder>> orders(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(plans.recentOrders(limit));
    }
}
