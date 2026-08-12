package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.VehicleProfile;
import io.github.jtconsole.operations.VehicleProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleProfileController {

    private final VehicleProfileService profiles;

    public VehicleProfileController(VehicleProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/{deviceId}/profile")
    public ApiResponse<VehicleProfile> profile(@PathVariable String deviceId) {
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("deviceId 不能为空");
        return profiles.find(deviceId.trim()).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "车辆不存在"));
    }
}
