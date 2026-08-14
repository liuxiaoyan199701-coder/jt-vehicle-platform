package io.github.jtconsole.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 给接口补充语义化的审计动作名与资源类型。
 *
 * <p>不标注也会被集中拦截器兜底记录（动作退化为 {@code METHOD /路径}），
 * 因此新增接口不会漏审计；标注只是让审计列表可读。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {

    /** 动作名，例如「下发拍照指令」。 */
    String value();

    /** 资源类型，例如 {@code vehicle}、{@code tenant}。 */
    String resourceType() default "";
}
