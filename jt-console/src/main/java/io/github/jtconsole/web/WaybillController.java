package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.repository.WaybillRepository;
import io.github.jtconsole.repository.WaybillRepository.WaybillPage;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicles/{deviceId}/waybills")
public class WaybillController {
    private static final int MAX_PAGE_SIZE = 100;

    private final WaybillRepository waybills;
    private final VehicleService vehicles;

    public WaybillController(WaybillRepository waybills, VehicleService vehicles) {
        this.waybills = waybills;
        this.vehicles = vehicles;
    }

    @GetMapping
    @RequirePermission(Permissions.VEHICLE_LIST)
    public ApiResponse<WaybillPage> list(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            DataScope scope) {
        // 先做归属校验：范围外设备与不存在设备必须完全相同，且不触达运单仓储。
        String canonicalId = vehicles.requireVisibleDevice(deviceId, scope);
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        return ApiResponse.ok(waybills.findByDevice(
                canonicalId, normalizedPage, normalizedSize, scope));
    }

    /** 返回 base64 原文，浏览器还原为精确字节下载；继续走统一鉴权与 token 刷新通道。 */
    @GetMapping("/{waybillId}/raw")
    @RequirePermission(Permissions.VEHICLE_LIST)
    public ApiResponse<Map<String, Object>> raw(
            @PathVariable String deviceId,
            @PathVariable long waybillId,
            DataScope scope) {
        String canonicalId = vehicles.requireVisibleDevice(deviceId, scope);
        return waybills.findRaw(waybillId, canonicalId, scope)
                .map(raw -> ApiResponse.ok(Map.<String, Object>of(
                        "base64", raw.rawBase64(),
                        "length", raw.rawLength(),
                        "fileName", "waybill-" + raw.id() + ".bin")))
                .orElseGet(() -> ApiResponse.error("4004", "运单不存在"));
    }
}
