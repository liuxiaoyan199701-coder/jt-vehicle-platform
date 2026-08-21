package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.AlarmLevel;
import io.github.jtconsole.domain.AlarmRule;
import io.github.jtconsole.domain.AlarmRuleType;
import io.github.jtconsole.operations.RuleService;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alarm-rules")
public class RuleController {

    private final RuleService rules;

    public RuleController(RuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    @RequirePermission(Permissions.RULE_LIST)
    public ApiResponse<List<AlarmRule>> list(DataScope scope) {
        return ApiResponse.ok(rules.findAll(scope));
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.RULE_LIST)
    public ApiResponse<AlarmRule> get(@PathVariable long id, DataScope scope) {
        return rules.findById(id, scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "规则不存在"));
    }

    @PostMapping
    @RequirePermission(Permissions.RULE_MANAGE)
    @Audited(value = "新增告警规则", resourceType = "alarm-rule")
    public ApiResponse<AlarmRule> create(
            @RequestBody RuleRequest body, AuthorizedPrincipal principal) {
        return ApiResponse.ok(rules.create(principal, body.toDomain()));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.RULE_MANAGE)
    @Audited(value = "编辑告警规则", resourceType = "alarm-rule")
    public ApiResponse<AlarmRule> update(
            @PathVariable long id, @RequestBody RuleRequest body, DataScope scope) {
        return rules.update(id, body.toDomain(), scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "规则不存在"));
    }

    @PutMapping("/{id}/vehicles")
    @RequirePermission(Permissions.RULE_MANAGE)
    @Audited(value = "调整规则车辆", resourceType = "alarm-rule")
    public ApiResponse<AlarmRule> replaceVehicles(
            @PathVariable long id, @RequestBody VehicleAssignments body, DataScope scope) {
        List<String> ids = body == null ? null : body.deviceIds();
        return rules.replaceVehicles(id, ids, scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "规则不存在"));
    }

    @PutMapping("/{id}/enabled")
    @RequirePermission(Permissions.RULE_MANAGE)
    @Audited(value = "启停告警规则", resourceType = "alarm-rule")
    public ApiResponse<AlarmRule> setEnabled(
            @PathVariable long id, @RequestBody EnabledRequest body, DataScope scope) {
        if (body == null || body.enabled() == null) throw new IllegalArgumentException("enabled 不能为空");
        return rules.setEnabled(id, body.enabled(), scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "规则不存在"));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.RULE_MANAGE)
    @Audited(value = "删除告警规则", resourceType = "alarm-rule")
    public ApiResponse<Void> delete(@PathVariable long id, DataScope scope) {
        return rules.delete(id, scope)
                ? ApiResponse.ok(null) : ApiResponse.error("4004", "规则不存在");
    }

    public record VehicleAssignments(List<String> deviceIds) {}
    public record EnabledRequest(Boolean enabled) {}

    public record RuleRequest(
            String name,
            String type,
            Double thresholdKph,
            Integer durationMinutes,
            String level,
            Boolean enabled,
            List<String> vehicleIds,
            Long tenantId) {

        AlarmRule toDomain() {
            return new AlarmRule(null, name,
                    AlarmRuleType.fromWire(type),
                    thresholdKph == null ? 0 : thresholdKph,
                    durationMinutes == null ? 0 : durationMinutes,
                    level == null ? null : AlarmLevel.valueOf(level.trim().toUpperCase()),
                    Boolean.TRUE.equals(enabled), vehicleIds, 0, tenantId, null, null);
        }
    }
}
