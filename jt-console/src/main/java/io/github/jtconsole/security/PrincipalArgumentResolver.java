package io.github.jtconsole.security;

import io.github.jtconsole.security.SessionTokenService.AuthenticatedSession;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 把授权上下文与数据范围注入控制器参数。
 *
 * <p>控制器显式接收 {@link DataScope} 并往下传，是租户隔离可被评审与测试看见的前提；
 * 换成从 ThreadLocal 里隐式取值，漏传的调用点就再也无法在代码里发现。
 */
@Component
public class PrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    private final AuthorizationResolver authorizations;

    public PrincipalArgumentResolver(AuthorizationResolver authorizations) {
        this.authorizations = authorizations;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return AuthorizedPrincipal.class.equals(type) || DataScope.class.equals(type);
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest request,
            WebDataBinderFactory binderFactory) {
        AuthorizedPrincipal principal = currentPrincipal(request);
        return DataScope.class.equals(parameter.getParameterType()) ? principal.scope() : principal;
    }

    private AuthorizedPrincipal currentPrincipal(NativeWebRequest request) {
        Object cached = request.getAttribute(
                PermissionEnforcementInterceptor.PRINCIPAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (cached instanceof AuthorizedPrincipal principal) {
            return principal;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedSession session)) {
            throw new IllegalStateException("控制器要求授权上下文，但当前请求未认证");
        }
        return authorizations.resolve(session.accountId())
                .orElseThrow(() -> new IllegalStateException("账号已失效，无法解析授权上下文"));
    }
}
