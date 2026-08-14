package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.AccountView;
import io.github.jtconsole.iam.AccountService;
import io.github.jtconsole.iam.AccountService.AccountRequest;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @RequirePermission(Permissions.SYSTEM_ACCOUNT_LIST)
    public ApiResponse<List<AccountView>> list(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String keyword,
            AuthorizedPrincipal principal) {
        return ApiResponse.ok(accounts.search(principal, tenantId, keyword));
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.SYSTEM_ACCOUNT_LIST)
    public ApiResponse<AccountView> get(@PathVariable long id, AuthorizedPrincipal principal) {
        return ApiResponse.ok(accounts.get(principal, id));
    }

    @PostMapping
    @RequirePermission(Permissions.SYSTEM_ACCOUNT_MANAGE)
    @Audited(value = "新增账号", resourceType = "account")
    public ApiResponse<AccountView> create(
            @RequestBody AccountRequest request, AuthorizedPrincipal principal) {
        return ApiResponse.ok(accounts.create(principal, request));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.SYSTEM_ACCOUNT_MANAGE)
    @Audited(value = "编辑账号", resourceType = "account")
    public ApiResponse<AccountView> update(
            @PathVariable long id, @RequestBody AccountRequest request,
            AuthorizedPrincipal principal) {
        return ApiResponse.ok(accounts.update(principal, id, request));
    }

    @PutMapping("/{id}/status")
    @RequirePermission(Permissions.SYSTEM_ACCOUNT_MANAGE)
    @Audited(value = "启停账号", resourceType = "account")
    public ApiResponse<Void> changeStatus(
            @PathVariable long id, @RequestBody Map<String, Boolean> body,
            AuthorizedPrincipal principal) {
        Boolean enabled = body == null ? null : body.get("enabled");
        if (enabled == null) {
            throw new IllegalArgumentException("enabled 不能为空");
        }
        accounts.changeStatus(principal, id, enabled);
        return ApiResponse.ok(null);
    }

    @PutMapping("/{id}/password")
    @RequirePermission(Permissions.SYSTEM_ACCOUNT_MANAGE)
    @Audited(value = "重置账号密码", resourceType = "account")
    public ApiResponse<Void> resetPassword(
            @PathVariable long id, @RequestBody Map<String, String> body,
            AuthorizedPrincipal principal) {
        accounts.resetPassword(principal, id, body == null ? null : body.get("newPassword"));
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.SYSTEM_ACCOUNT_MANAGE)
    @Audited(value = "删除账号", resourceType = "account")
    public ApiResponse<Void> delete(@PathVariable long id, AuthorizedPrincipal principal) {
        accounts.delete(principal, id);
        return ApiResponse.ok(null);
    }
}
