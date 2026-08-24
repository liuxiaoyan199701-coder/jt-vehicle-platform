package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.TerminalPage;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.operations.TerminalQueryService;
import io.github.jtconsole.operations.VehicleService;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 终端管理：连接过网关的终端清单，以及从清单直接建档。
 *
 * <p>清单权限沿用 {@code vehicle:list}——能看车辆档案的人就该能看到「还没建档的终端」，
 * 不为一个发现页再造权限码；建档权限沿用 {@code vehicle:create}，
 * 与车辆档案页新建是同一个动作。
 */
@RestController
@RequestMapping("/api/terminals")
public class TerminalController {

    private final TerminalQueryService terminals;

    public TerminalController(TerminalQueryService terminals) {
        this.terminals = terminals;
    }

    /**
     * @param archived 是否已建档；留空表示两者都要
     * @param start    最近一次注册/鉴权时间的下界。注意不是「最近在线」——
     *                 长连不断的终端不会刷新它
     */
    @GetMapping
    @RequirePermission(Permissions.VEHICLE_LIST)
    public ApiResponse<TerminalPage> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(required = false) Boolean online,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            DataScope scope) {
        return ApiResponse.ok(
                terminals.search(keyword, archived, online, start, end, page, pageSize, scope));
    }

    @PostMapping("/{deviceId}/archive")
    @RequirePermission(Permissions.VEHICLE_CREATE)
    @Audited(value = "从终端管理建档", resourceType = "vehicle")
    public ApiResponse<Vehicle> archive(
            @PathVariable String deviceId,
            @RequestBody(required = false) VehicleService.VehicleRequest body,
            AuthorizedPrincipal principal) {
        return ApiResponse.ok(terminals.archive(principal, deviceId, body));
    }
}
