package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.AuditContext;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.Account;
import io.github.jtconsole.domain.Role;
import io.github.jtconsole.iam.AccountService;
import io.github.jtconsole.security.AccountAuthenticationService;
import io.github.jtconsole.security.AuthenticatedOnly;
import io.github.jtconsole.security.AuthenticationRateLimiter;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.SessionTokenService;
import io.github.jtconsole.security.SessionTokenService.AuthenticatedSession;
import io.github.jtconsole.security.SessionTokenService.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String AUTHENTICATION_ERROR_CODE = "4001";
    private static final String RATE_LIMIT_ERROR_CODE = "4290";
    /**
     * 登录失败一律用同一条文案。区分「用户不存在」「密码错误」「账号被禁用」「租户到期」
     * 会把登录页变成账户与租户状态的枚举器。
     */
    private static final String AUTHENTICATION_ERROR_MESSAGE = "用户名或密码错误";
    private static final String REFRESH_ERROR_MESSAGE = "刷新凭据无效或已过期";
    private static final String RATE_LIMIT_ERROR_MESSAGE = "认证尝试过于频繁，请稍后重试";

    private final AccountAuthenticationService authentication;
    private final AccountService accounts;
    private final SessionTokenService tokens;
    private final AuthenticationRateLimiter rateLimiter;

    public AuthController(
            AccountAuthenticationService authentication,
            AccountService accounts,
            SessionTokenService tokens,
            AuthenticationRateLimiter rateLimiter) {
        this.authentication = authentication;
        this.accounts = accounts;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    @Audited(value = "登录", resourceType = "account")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        Map<String, String> input = body == null ? Map.of() : body;
        String username = input.containsKey("userName")
                ? input.get("userName")
                : input.get("username");
        String password = input.get("password");
        String source = request.getRemoteAddr();
        AuditContext.actor(username, null, null);

        if (rateLimiter.isLoginBlocked(source, username)) {
            return tooManyRequests();
        }
        Optional<Account> account = authentication.authenticate(username, password);
        if (account.isEmpty()) {
            rateLimiter.recordLoginFailure(source, username);
            return unauthorized(AUTHENTICATION_ERROR_MESSAGE);
        }

        Account authenticated = account.get();
        rateLimiter.recordLoginSuccess(source, username);
        authentication.recordSuccessfulLogin(authenticated.id());
        AuditContext.actor(
                authenticated.username(), authenticated.id(), authenticated.tenantId());
        TokenPair pair = tokens.issue(
                authenticated.id(), authenticated.username(), authenticated.tenantId());
        return ResponseEntity.ok(ApiResponse.ok(tokenBody(pair)));
    }

    @GetMapping("/getUserInfo")
    @AuthenticatedOnly
    public ApiResponse<Map<String, Object>> userInfo(AuthorizedPrincipal principal) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("userId", String.valueOf(principal.accountId()));
        info.put("userName", principal.username());
        info.put("displayName", principal.displayName());
        info.put("tenantId", principal.tenantId());
        info.put("tenantName", principal.tenantName());
        info.put("platform", principal.platform());
        // soybean-admin 的静态路由按 roles 过滤，因此角色码带 R_ 前缀返回。
        info.put("roles", principal.roles().stream()
                .map(role -> "R_" + role.code())
                .toList());
        info.put("roleDetails", principal.roles());
        info.put("buttons", principal.permissions().stream().sorted().toList());
        info.put("permissions", principal.permissions().stream().sorted().toList());
        return ApiResponse.ok(info);
    }

    @PostMapping("/refreshToken")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refresh(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        String refreshToken = body == null ? null : body.get("refreshToken");
        String source = request.getRemoteAddr();

        if (rateLimiter.isRefreshBlocked(source, refreshToken)) {
            return tooManyRequests();
        }
        return tokens.rotateRefreshToken(refreshToken)
                .map(rotated -> {
                    rateLimiter.recordRefreshSuccess(source, refreshToken);
                    return ResponseEntity.ok(ApiResponse.ok(tokenBody(rotated)));
                })
                .orElseGet(() -> {
                    rateLimiter.recordRefreshFailure(source, refreshToken);
                    return unauthorized(REFRESH_ERROR_MESSAGE);
                });
    }

    @PostMapping("/logout")
    @AuthenticatedOnly
    @Audited(value = "注销", resourceType = "account")
    public ApiResponse<Void> logout(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedSession authenticatedSession) {
            tokens.revokeSession(authenticatedSession);
        }
        return ApiResponse.ok(null);
    }

    /**
     * 修改自己的密码。只读账号同样必须能改自己的密码，因此走 {@link AuthenticatedOnly} 而非权限码。
     */
    @PostMapping("/changePassword")
    @AuthenticatedOnly
    @Audited(value = "修改本人密码", resourceType = "account")
    public ApiResponse<Void> changePassword(
            @RequestBody(required = false) Map<String, String> body,
            AuthorizedPrincipal principal,
            Authentication authentication) {
        Map<String, String> input = body == null ? Map.of() : body;
        String currentSessionId = authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedSession session
                ? session.sessionId()
                : null;
        accounts.changeOwnPassword(
                principal.accountId(),
                input.get("oldPassword"),
                input.get("newPassword"),
                currentSessionId);
        return ApiResponse.ok(null);
    }

    private static Map<String, Object> tokenBody(TokenPair pair) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", pair.token());
        body.put("refreshToken", pair.refreshToken());
        body.put("accessTokenExpiresAt", pair.accessTokenExpiresAt().toString());
        body.put("refreshTokenExpiresAt", pair.refreshTokenExpiresAt().toString());
        return body;
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> unauthorized(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(AUTHENTICATION_ERROR_CODE, message));
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> tooManyRequests() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(RATE_LIMIT_ERROR_CODE, RATE_LIMIT_ERROR_MESSAGE));
    }
}
