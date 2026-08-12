package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.FleetDetails;
import io.github.jtconsole.domain.FleetSummary;
import io.github.jtconsole.operations.FleetService;
import io.github.jtconsole.operations.FleetService.FleetInput;
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
    public ApiResponse<List<FleetSummary>> list(
            @RequestParam(required = false, defaultValue = "") String keyword) {
        return ApiResponse.ok(fleets.findAll(keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<FleetDetails> get(@PathVariable long id) {
        return ApiResponse.ok(fleets.find(id));
    }

    @PostMapping
    public ApiResponse<FleetDetails> create(@RequestBody FleetRequest request) {
        return ApiResponse.ok(fleets.create(request.toInput()));
    }

    @PutMapping("/{id}")
    public ApiResponse<FleetDetails> update(
            @PathVariable long id, @RequestBody FleetRequest request) {
        return ApiResponse.ok(fleets.update(id, request.toInput()));
    }

    @PutMapping("/{id}/vehicles")
    public ApiResponse<FleetDetails> replaceVehicles(
            @PathVariable long id, @RequestBody VehicleAssignments request) {
        return ApiResponse.ok(fleets.replaceMembers(id,
                request == null ? null : request.deviceIds()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        fleets.delete(id);
        return ApiResponse.ok(null);
    }

    public record FleetRequest(
            String code, String name, String manager, String contactPhone, String remark) {
        FleetInput toInput() {
            return new FleetInput(code, name, manager, contactPhone, remark);
        }
    }

    public record VehicleAssignments(List<String> deviceIds) {}
}
