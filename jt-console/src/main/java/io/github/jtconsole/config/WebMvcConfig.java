package io.github.jtconsole.config;

import io.github.jtconsole.audit.AuditCaptureInterceptor;
import io.github.jtconsole.security.PermissionEnforcementInterceptor;
import io.github.jtconsole.security.PrincipalArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    private final PermissionEnforcementInterceptor permissions;
    private final AuditCaptureInterceptor audit;
    private final PrincipalArgumentResolver principals;

    public WebMvcConfig(
            PermissionEnforcementInterceptor permissions,
            AuditCaptureInterceptor audit,
            PrincipalArgumentResolver principals) {
        this.permissions = permissions;
        this.audit = audit;
        this.principals = principals;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 权限拦截必须先于审计：被拒绝的请求也要留痕，而审计需要知道判定结果。
        registry.addInterceptor(audit).addPathPatterns("/api/**");
        registry.addInterceptor(permissions).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(principals);
    }
}
