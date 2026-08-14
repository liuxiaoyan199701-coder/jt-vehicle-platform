package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.FleetDetails;
import io.github.jtconsole.domain.FleetSummary;
import io.github.jtconsole.operations.FleetService;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.operations.FleetService.FleetInput;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fleets")
public class FleetController {

    private final FleetService fleets;

    public FleetController(FleetService fleets) {
        this.fleets = fleets;
    }

    @GetMapping
    @RequirePermission(Permissions.FLEET_LIST)
    public ApiResponse<List<FleetSummary>> list(
            @RequestParam(required = false, defaultValue = "") String keyword,
            DataScope scope) {
        return ApiResponse.ok(fleets.findAll(keyword, scope));
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.FLEET_LIST)
    public ApiResponse<FleetDetails> get(@PathVariable long id, DataScope scope) {
        return ApiResponse.ok(fleets.find(id, scope));
    }

    @PostMapping
    @RequirePermission(Permissions.FLEET_MANAGE)
    @Audited(value = "新增车队", resourceType = "fleet")
    public ApiResponse<FleetDetails> create(
            @RequestBody FleetRequest request, AuthorizedPrincipal principal) {
        return ApiResponse.ok(fleets.create(principal, request.toInput()));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.FLEET_MANAGE)
    @Audited(value = "编辑车队", resourceType = "fleet")
    public ApiResponse<FleetDetails> update(
            @PathVariable long id, @RequestBody FleetRequest request,
            AuthorizedPrincipal principal) {
        return ApiResponse.ok(fleets.update(principal, id, request.toInput()));
    }

    @PutMapping("/{id}/vehicles")
    @RequirePermission(Permissions.FLEET_MANAGE)
    @Audited(value = "调整车队成员", resourceType = "fleet")
    public ApiResponse<FleetDetails> replaceVehicles(
            @PathVariable long id, @RequestBody VehicleAssignments request, DataScope scope) {
        return ApiResponse.ok(fleets.replaceMembers(id,
                request == null ? null : request.deviceIds(), scope));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.FLEET_MANAGE)
    @Audited(value = "删除车队", resourceType = "fleet")
    public ApiResponse<Void> delete(@PathVariable long id, DataScope scope) {
        fleets.delete(id, scope);
        return ApiResponse.ok(null);
    }

    public record FleetRequest(
            String code, String name, String manager, String contactPhone, String remark,
            Long tenantId) {
        FleetInput toInput() {
            return new FleetInput(code, name, manager, contactPhone, remark, tenantId);
        }
    }

    public record VehicleAssignments(List<String> deviceIds) {}
}
