package io.github.jtconsole.security;

import io.github.jtconsole.security.SessionTokenService.AuthenticatedSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 权限码的统一强制点。
 *
 * <p>三条规则，按顺序生效：
 * <ol>
 *   <li>会话对应的账号已被禁用或删除 → 401（token 尚未过期也不放行）；</li>
 *   <li>非 GET 且未标注 {@link AuthenticatedOnly} 时，一个写权限都没有的账号直接拒绝——
 *       这条集中拦截让「只读」对新增接口自动生效；</li>
 *   <li>标注了 {@link RequirePermission} 则按权限码判定；两个注解都没有的写接口
 *       只有平台管理员可访问，避免新增接口因忘记授权而对全体租户敞开。</li>
 * </ol>
 */
@Component
public class PermissionEnforcementInterceptor implements HandlerInterceptor {

    /** 解析结果挂到请求属性上，供参数解析器复用，避免一次请求解析两遍。 */
    static final String PRINCIPAL_ATTRIBUTE =
            PermissionEnforcementInterceptor.class.getName() + ".principal";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PermissionEnforcementInterceptor.class);

    private final AuthorizationResolver authorizations;
    private final JsonSecurityResponseWriter responses;

    public PermissionEnforcementInterceptor(
            AuthorizationResolver authorizations,
            JsonSecurityResponseWriter responses) {
        this.authorizations = authorizations;
        this.responses = responses;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Optional<AuthenticatedSession> session = currentSession();
        if (session.isEmpty()) {
            // 未认证请求由安全过滤链负责；能走到这里的只有 permitAll 的登录与刷新。
            return true;
        }

        Optional<AuthorizedPrincipal> resolved = authorizations.resolve(session.get().accountId());
        if (resolved.isEmpty()) {
            responses.unauthorized(response);
            return false;
        }
        AuthorizedPrincipal principal = resolved.get();
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);

        boolean selfService = AnnotatedElementUtils.hasAnnotation(
                method.getMethod(), AuthenticatedOnly.class)
                || AnnotatedElementUtils.hasAnnotation(
                        method.getBeanType(), AuthenticatedOnly.class);
        if (selfService) {
            return true;
        }

        boolean writeRequest = isWriteRequest(request);
        if (writeRequest && principal.readOnly()) {
            return deny(response, principal, method, "只读账号不能执行写操作");
        }

        RequirePermission required = AnnotatedElementUtils.findMergedAnnotation(
                method.getMethod(), RequirePermission.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(
                    method.getBeanType(), RequirePermission.class);
        }
        if (required == null) {
            if (!writeRequest) {
                return true;
            }
            return principal.platform()
                    ? true
                    : deny(response, principal, method, "接口未声明权限码，默认仅平台管理员可访问");
        }

        boolean granted = required.any()
                ? principal.hasAnyPermission(required.value())
                : hasAll(principal, required.value());
        return granted || deny(response, principal, method, "缺少所需权限码");
    }

    private static boolean hasAll(AuthorizedPrincipal principal, String[] codes) {
        for (String code : codes) {
            if (!principal.hasPermission(code)) {
                return false;
            }
        }
        return true;
    }

    private boolean deny(
            HttpServletResponse response,
            AuthorizedPrincipal principal,
            HandlerMethod method,
            String reason) throws IOException {
        LOGGER.debug("拒绝访问 {}#{}：accountId={} 原因={}",
                method.getBeanType().getSimpleName(),
                method.getMethod().getName(),
                principal.accountId(),
                reason);
        responses.forbidden(response);
        return false;
    }

    private static boolean isWriteRequest(HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        return !(HttpMethod.GET.equals(method)
                || HttpMethod.HEAD.equals(method)
                || HttpMethod.OPTIONS.equals(method));
    }

    private static Optional<AuthenticatedSession> currentSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedSession session)) {
            return Optional.empty();
        }
        return Optional.of(session);
    }
}
