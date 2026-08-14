package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.DashboardOverview;
import io.github.jtconsole.operations.DashboardService;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/overview")
    @RequirePermission(Permissions.DASHBOARD_VIEW)
    public ApiResponse<DashboardOverview> overview(DataScope scope) {
        return ApiResponse.ok(dashboard.overview(scope));
    }
}
