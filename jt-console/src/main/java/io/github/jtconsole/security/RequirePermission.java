package io.github.jtconsole.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口所需的权限码，由 {@link PermissionEnforcementInterceptor} 统一校验。
 *
 * <p>没有声明本注解也没有 {@link AuthenticatedOnly} 的写接口（非 GET）默认只有平台管理员可访问，
 * 新增接口因此不会因为忘记授权而对全体租户敞开。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePermission {

    /** 所需权限码。 */
    String[] value();

    /** 多个权限码时的判定方式：默认满足任一即可，置 false 表示必须全部具备。 */
    boolean any() default true;
}
