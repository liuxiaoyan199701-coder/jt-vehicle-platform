package io.github.jtconsole.audit;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.AuditEntry;
import io.github.jtconsole.security.SessionTokenService.AuthenticatedSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 集中采集写操作审计。
 *
 * <p>兜底策略：所有 {@code /api/**} 的非 GET 请求都记录，未标注 {@link Audited} 的动作名
 * 退化为「方法 + 路径」。新增写接口因此不会因为忘记标注而漏审计。
 *
 * <p>刻意不记录请求体：为审计去包装每个请求的输入流，代价会落在全部业务请求上，
 * 而规格要求的字段（操作人、租户、动作、对象、来源 IP、结果、耗时）都不需要请求体。
 * 目标对象取自路径变量，请求体里才有目标的少数接口通过 {@link AuditContext} 显式补充。
 */
@Component
public class AuditCaptureInterceptor implements HandlerInterceptor {

    private static final String START_NANOS = AuditCaptureInterceptor.class.getName() + ".start";
    /** 出现在查询串里就整体打码的参数名，避免凭据随 URL 落库。 */
    private static final Set<String> SENSITIVE_PARAMETERS = Set.of(
            "password", "newpassword", "oldpassword", "token", "refreshtoken",
            "accesstoken", "key", "secret", "signature");

    private final AuditRecorder recorder;
    private final Clock clock;

    @Autowired
    public AuditCaptureInterceptor(AuditRecorder recorder) {
        this(recorder, Clock.systemUTC());
    }

    AuditCaptureInterceptor(AuditRecorder recorder, Clock clock) {
        this.recorder = recorder;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_NANOS, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception failure) {
        if (!isWriteRequest(request)) {
            return;
        }
        Optional<AuthenticatedSession> session = currentSession();
        int status = response.getStatus();
        recorder.record(new AuditEntry(
                0L,
                clock.instant().toString(),
                longAttribute(request, AuditContext.ACTOR_TENANT_ID,
                        session.map(AuthenticatedSession::tenantId).orElse(null)),
                longAttribute(request, AuditContext.ACTOR_ACCOUNT_ID,
                        session.map(AuthenticatedSession::accountId).orElse(null)),
                stringAttribute(request, AuditContext.ACTOR_USERNAME,
                        session.map(AuthenticatedSession::username).orElse(null)),
                action(handler, request),
                resourceType(handler, request),
                resourceId(request),
                request.getMethod(),
                request.getRequestURI(),
                detail(request),
                request.getRemoteAddr(),
                resultOf(request, status, failure),
                status,
                elapsedMillis(request)));
    }

    private static Long longAttribute(HttpServletRequest request, String key, Long fallback) {
        Object explicit = request.getAttribute(key);
        return explicit instanceof Long value ? value : fallback;
    }

    private static String stringAttribute(HttpServletRequest request, String key, String fallback) {
        Object explicit = request.getAttribute(key);
        return explicit instanceof String value ? value : fallback;
    }

    /**
     * 业务失败走 HTTP 200 + 错误码是控制台的既有约定，因此结果判定必须先看业务码：
     * 只看状态码会把「越权被拒」记成成功，之后按结果筛选就再也找不出越权尝试。
     */
    private static String resultOf(HttpServletRequest request, int status, Exception failure) {
        if (failure != null || status >= 500) {
            return AuditEntry.FAILURE;
        }
        if (status == HttpServletResponse.SC_FORBIDDEN
                || status == HttpServletResponse.SC_UNAUTHORIZED) {
            return AuditEntry.DENIED;
        }
        if (status >= 400) {
            return AuditEntry.FAILURE;
        }
        Object businessCode = request.getAttribute(AuditContext.BUSINESS_CODE);
        if (businessCode instanceof String code && !ApiResponse.SUCCESS_CODE.equals(code)) {
            return AuditEntry.FAILURE;
        }
        return AuditEntry.SUCCESS;
    }

    private static String action(Object handler, HttpServletRequest request) {
        Audited audited = auditedAnnotation(handler);
        if (audited != null && !audited.value().isBlank()) {
            return audited.value();
        }
        return request.getMethod() + " " + request.getRequestURI();
    }

    private static String resourceType(Object handler, HttpServletRequest request) {
        Audited audited = auditedAnnotation(handler);
        if (audited != null && !audited.resourceType().isBlank()) {
            return audited.resourceType();
        }
        Object explicit = request.getAttribute(AuditContext.RESOURCE_TYPE);
        return explicit instanceof String value ? value : null;
    }

    private static Audited auditedAnnotation(Object handler) {
        if (handler instanceof HandlerMethod method) {
            return AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), Audited.class);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String resourceId(HttpServletRequest request) {
        Object explicit = request.getAttribute(AuditContext.RESOURCE_ID);
        if (explicit instanceof String value) {
            return value;
        }
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(variables instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        ((Map<String, String>) map).values().forEach(values::add);
        return String.join(",", values);
    }

    private static String detail(HttpServletRequest request) {
        Object explicit = request.getAttribute(AuditContext.DETAIL);
        String query = maskedQuery(request.getQueryString());
        if (explicit instanceof String value && !value.isBlank()) {
            return query == null ? value : value + " | " + query;
        }
        return query;
    }

    /** 查询串里的凭据类参数整体打码，其余原样保留以便定位问题。 */
    private static String maskedQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        StringBuilder masked = new StringBuilder();
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            boolean sensitive = SENSITIVE_PARAMETERS.contains(
                    name.toLowerCase(java.util.Locale.ROOT));
            if (!masked.isEmpty()) {
                masked.append('&');
            }
            masked.append(name).append('=').append(sensitive ? "***" : valueOf(pair, separator));
        }
        return masked.length() > 500 ? masked.substring(0, 500) : masked.toString();
    }

    private static String valueOf(String pair, int separator) {
        return separator < 0 ? "" : pair.substring(separator + 1);
    }

    private static Integer elapsedMillis(HttpServletRequest request) {
        Object start = request.getAttribute(START_NANOS);
        if (!(start instanceof Long startNanos)) {
            return null;
        }
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
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
