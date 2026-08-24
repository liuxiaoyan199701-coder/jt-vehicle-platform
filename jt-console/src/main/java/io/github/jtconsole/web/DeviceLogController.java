package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.DeviceLog;
import io.github.jtconsole.domain.DeviceLogPage;
import io.github.jtconsole.operations.DeviceLogQueryService;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备日志查询接口。
 *
 * <p>{@code deviceId} 必填不是为了省事——日志表按「设备 + 时间」建索引，不带设备号的查询会
 * 退化成全表扫，而且跨设备翻日志本来也不是排障的做法。
 */
@RestController
@RequestMapping("/api/device-logs")
public class DeviceLogController {

    private final DeviceLogQueryService logs;

    public DeviceLogController(DeviceLogQueryService logs) {
        this.logs = logs;
    }

    @GetMapping
    @RequirePermission(Permissions.VEHICLE_LIST)
    public ApiResponse<DeviceLogPage> search(
            @RequestParam String deviceId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String msgId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            DataScope scope) {
        return ApiResponse.ok(logs.query(
                deviceId, start, end, direction, msgId, keyword, page, pageSize, scope));
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.VEHICLE_LIST)
    public ApiResponse<DeviceLog> get(@PathVariable long id, DataScope scope) {
        return logs.findById(id, scope).map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "日志记录不存在"));
    }
}
