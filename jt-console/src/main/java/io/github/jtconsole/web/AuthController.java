package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.security.AdminCredentialService;
import io.github.jtconsole.security.AuthenticationRateLimiter;
import io.github.jtconsole.security.SessionTokenService;
import io.github.jtconsole.security.SessionTokenService.AuthenticatedSession;
import io.github.jtconsole.security.SessionTokenService.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String AUTHENTICATION_ERROR_MESSAGE = "用户名或密码错误";
    private static final String REFRESH_ERROR_MESSAGE = "刷新凭据无效或已过期";
    private static final String RATE_LIMIT_ERROR_MESSAGE = "认证尝试过于频繁，请稍后重试";

    private final AdminCredentialService credentials;
    private final SessionTokenService tokens;
    private final AuthenticationRateLimiter rateLimiter;

    public AuthController(
            AdminCredentialService credentials,
            SessionTokenService tokens,
            AuthenticationRateLimiter rateLimiter) {
        this.credentials = credentials;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        Map<String, String> input = body == null ? Map.of() : body;
        String username = input.containsKey("userName")
                ? input.get("userName")
                : input.get("username");
        String password = input.get("password");
        String source = request.getRemoteAddr();

        if (rateLimiter.isLoginBlocked(source, username)) {
            return tooManyRequests();
        }
        if (!credentials.authenticate(username, password)) {
            rateLimiter.recordLoginFailure(source, username);
            return unauthorized(AUTHENTICATION_ERROR_MESSAGE);
        }

        rateLimiter.recordLoginSuccess(source, username);
        return ResponseEntity.ok(ApiResponse.ok(tokenBody(tokens.issue(credentials.username()))));
    }

    @GetMapping("/getUserInfo")
    public ApiResponse<Map<String, Object>> userInfo(Authentication authentication) {
        return ApiResponse.ok(Map.of(
                "userId", "1",
                "userName", authentication.getName(),
                "roles", List.of("R_ADMIN"),
                "buttons", List.of()));
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
    public ApiResponse<Void> logout(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedSession authenticatedSession) {
            tokens.revokeSession(authenticatedSession);
        }
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
