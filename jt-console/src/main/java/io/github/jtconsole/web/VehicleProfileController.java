package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.VehicleProfile;
import io.github.jtconsole.operations.VehicleProfileService;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
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
    @RequirePermission(Permissions.VEHICLE_LIST)
    public ApiResponse<VehicleProfile> profile(
            @PathVariable String deviceId, DataScope scope) {
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("deviceId 不能为空");
        // 未建档设备也返回详情（vehicle 字段为 null）由前端降级展示，但这只对平台管理员成立；
        // 租户用户看不到范围外设备，返回与「不存在」完全一致的语义。
        return profiles.find(deviceId.trim(), scope)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "车辆不存在"));
    }
}
