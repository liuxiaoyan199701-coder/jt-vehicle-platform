package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.AuditContext;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.TenantRegistration;
import io.github.jtconsole.iam.CaptchaService;
import io.github.jtconsole.iam.RegistrationService;
import io.github.jtconsole.iam.RegistrationService.RegistrationRequest;
import io.github.jtconsole.security.AuthenticationRateLimiter;
import io.github.jtconsole.security.AuthorizedPrincipal;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自助注册（公开）与注册审批（平台管理员）。
 *
 * <p>公开部分受图形验证码、来源 IP 限流与「入口开关」三重约束，且注册成功也只是进入待审批，
 * 不产生任何可登录的账号。
 */
@RestController
@RequestMapping("/api")
public class RegistrationController {

    private static final String RATE_LIMIT_CODE = "4290";

    private final RegistrationService registrations;
    private final AuthenticationRateLimiter rateLimiter;

    public RegistrationController(
            RegistrationService registrations, AuthenticationRateLimiter rateLimiter) {
        this.registrations = registrations;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/public/registration/captcha")
    public ApiResponse<Map<String, Object>> captcha() {
        CaptchaService.Challenge challenge = registrations.issueCaptcha();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("captchaToken", challenge.token());
        body.put("image", challenge.imageDataUrl());
        body.put("expiresAt", challenge.expiresAt().toString());
        return ApiResponse.ok(body);
    }

    @PostMapping("/public/registration")
    @Audited(value = "提交注册申请", resourceType = "registration")
    public ResponseEntity<ApiResponse<Void>> submit(
            @RequestBody RegistrationRequest request, HttpServletRequest httpRequest) {
        String source = httpRequest.getRemoteAddr();
        AuditContext.actor(request == null ? null : request.username(), null, null);
        // 复用登录限流器：公开写入口与登录面对的是同一类脚本化滥用。
        if (rateLimiter.isLoginBlocked(source, "registration")) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error(RATE_LIMIT_CODE, "提交过于频繁，请稍后重试"));
        }
        try {
            registrations.submit(request, source);
        } catch (RuntimeException failure) {
            rateLimiter.recordLoginFailure(source, "registration");
            throw failure;
        }
        rateLimiter.recordLoginSuccess(source, "registration");
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/platform/registrations")
    @RequirePermission(Permissions.PLATFORM_REGISTRATION_REVIEW)
    public ApiResponse<List<TenantRegistration>> list(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(registrations.list(status));
    }

    @PostMapping("/platform/registrations/{id}/approve")
    @RequirePermission(Permissions.PLATFORM_REGISTRATION_REVIEW)
    @Audited(value = "审批通过注册", resourceType = "registration")
    public ApiResponse<Void> approve(
            @PathVariable long id, @RequestBody ApproveBody body, AuthorizedPrincipal principal) {
        registrations.approve(
                id,
                body == null ? null : body.planId(),
                body == null ? null : body.months(),
                principal.username());
        return ApiResponse.ok(null);
    }

    @PostMapping("/platform/registrations/{id}/reject")
    @RequirePermission(Permissions.PLATFORM_REGISTRATION_REVIEW)
    @Audited(value = "拒绝注册申请", resourceType = "registration")
    public ApiResponse<Void> reject(
            @PathVariable long id, @RequestBody RejectBody body, AuthorizedPrincipal principal) {
        registrations.reject(id, body == null ? null : body.reason(), principal.username());
        return ApiResponse.ok(null);
    }

    public record ApproveBody(Long planId, Integer months) {}

    public record RejectBody(String reason) {}
}
