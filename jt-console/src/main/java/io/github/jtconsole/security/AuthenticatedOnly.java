package io.github.jtconsole.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「任何已认证账号都可访问」的自助类接口：注销、查看自己的用户信息、
 * 修改自己的密码、读取本租户生效配置。
 *
 * <p>这类接口不受「只读账号禁止非 GET」的集中拦截约束——只读用户也必须能改自己的密码。
 * 用它替代「不声明权限码」，是为了让豁免成为一个显式动作而不是遗漏。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AuthenticatedOnly {
}
