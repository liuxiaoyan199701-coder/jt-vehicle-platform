package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.iam.ConfigKeys;
import io.github.jtconsole.iam.ConfigKeys.ConfigKeyDefinition;
import io.github.jtconsole.iam.TenantConfigService;
import io.github.jtconsole.security.AuthenticatedOnly;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config")
public class TenantConfigController {

    private final TenantConfigService configs;

    public TenantConfigController(TenantConfigService configs) {
        this.configs = configs;
    }

    /**
     * 当前会话租户的生效配置。前端据此在运行时加载地图 SDK 与平台名称，
     * 因此任何已认证账号都可读——只读用户同样要看地图。
     */
    @GetMapping("/effective")
    @AuthenticatedOnly
    public ApiResponse<Map<String, String>> effective(AuthorizedPrincipal principal) {
        return ApiResponse.ok(configs.effectiveFor(principal));
    }

    @GetMapping("/keys")
    @RequirePermission(Permissions.SYSTEM_CONFIG_VIEW)
    public ApiResponse<List<ConfigKeyDefinition>> keys() {
        return ApiResponse.ok(ConfigKeys.catalog());
    }

    @GetMapping("/overrides")
    @RequirePermission(Permissions.SYSTEM_CONFIG_VIEW)
    public ApiResponse<Map<String, String>> overrides(
            @RequestParam(required = false) Long tenantId, AuthorizedPrincipal principal) {
        return ApiResponse.ok(configs.overridesFor(principal, tenantId));
    }

    @PutMapping("/overrides")
    @RequirePermission({Permissions.SYSTEM_CONFIG_MANAGE, Permissions.PLATFORM_CONFIG_MANAGE})
    @Audited(value = "修改配置", resourceType = "config")
    public ApiResponse<Void> save(
            @RequestParam(required = false) Long tenantId,
            @RequestBody Map<String, String> values,
            AuthorizedPrincipal principal) {
        configs.save(principal, tenantId, values);
        return ApiResponse.ok(null);
    }
}
