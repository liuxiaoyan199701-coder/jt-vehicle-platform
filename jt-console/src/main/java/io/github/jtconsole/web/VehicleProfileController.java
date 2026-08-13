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
        String canonical = deviceId.trim();
        return profiles.find(canonical).map(ApiResponse::ok)
                // 设备在线但未建档是常见状态，消息要能指导下一步动作
                .orElseGet(() -> ApiResponse.error("4004",
                        "设备未建档（终端号 " + canonical + "），请先在车辆档案中新增该车辆。"));
    }
}
