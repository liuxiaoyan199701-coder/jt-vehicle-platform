package io.github.jtconsole.web;

import io.github.jtconsole.ai.briefing.BriefingService;
import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.DashboardOverview;
import io.github.jtconsole.operations.DashboardService;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;
    private final BriefingService briefings;

    public DashboardController(DashboardService dashboard, BriefingService briefings) {
        this.dashboard = dashboard;
        this.briefings = briefings;
    }

    @GetMapping("/overview")
    @RequirePermission(Permissions.DASHBOARD_VIEW)
    public ApiResponse<DashboardOverview> overview(DataScope scope) {
        return ApiResponse.ok(dashboard.overview(scope));
    }

    /**
     * 今日要点。读缓存，秒开。
     *
     * <p>返回的要点已按调用者的数据范围过滤：引用了范围外车辆的整条丢弃，
     * 范围不覆盖整个租户时不给聚合结论。被过滤过时 {@code filtered} 为 true，
     * 前端据此说明「已按你的数据范围过滤」——免得用户以为平台漏报。
     */
    @GetMapping("/briefing")
    @RequirePermission(Permissions.DASHBOARD_VIEW)
    public ApiResponse<BriefingService.Briefing> briefing(
            DataScope scope, AuthorizedPrincipal principal) {
        return ApiResponse.ok(briefings.read(scopeIdOf(principal), scope));
    }

    /**
     * 手动重新分析。
     *
     * <p>需要写权限：它会真的调一次模型，是有成本的操作。只读账号能看不能刷。
     */
    @PostMapping("/briefing/refresh")
    @RequirePermission(Permissions.DASHBOARD_VIEW)
    public ApiResponse<BriefingService.Briefing> refresh(
            DataScope scope, AuthorizedPrincipal principal) {
        long scopeId = scopeIdOf(principal);
        briefings.generateFor(scopeId);
        return ApiResponse.ok(briefings.read(scopeId, scope));
    }

    /**
     * 要点归属哪一份缓存。
     *
     * <p>平台管理员不属于任何租户，落到平台级那一份——它是跨租户运维的实际使用者，
     * 在单租户部署里往往就是唯一的使用者。让它永远看到空简报，等于功能对真正要用的人不可见。
     *
     * <p>映射本身在 {@link BriefingService#scopeIdOf} 上，与主动通知共用同一份。
     */
    private static long scopeIdOf(AuthorizedPrincipal principal) {
        return BriefingService.scopeIdOf(principal);
    }
}
