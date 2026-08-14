package io.github.jtconsole.web;

import io.github.jtconsole.domain.AuditEntry;
import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.repository.AuditRepository;
import io.github.jtconsole.repository.AuditRepository.AuditQuery;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志检索。
 *
 * <p>刻意只有 GET：审计记录不提供任何修改或删除接口，唯一的删除路径是按保留期的定时清理。
 */
@RestController
@RequestMapping("/api/system/audit")
public class AuditController {

    private final AuditRepository audits;

    public AuditController(AuditRepository audits) {
        this.audits = audits;
    }

    @GetMapping
    @RequirePermission(Permissions.SYSTEM_AUDIT_VIEW)
    public ApiResponse<Map<String, Object>> search(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            AuthorizedPrincipal principal) {
        // 租户过滤按会话强制填入：请求里的 tenantId 只有平台管理员说了算。
        Long effectiveTenant = principal.platform() ? tenantId : principal.tenantId();
        AuditQuery query = new AuditQuery(
                effectiveTenant, username, action, resourceType, result, from, to, page, size);
        List<AuditEntry> items = audits.search(query);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", items);
        body.put("total", audits.count(query));
        body.put("current", query.page());
        body.put("size", query.size());
        return ApiResponse.ok(body);
    }
}
