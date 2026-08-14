package io.github.jtconsole.audit;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 让控制器给当前请求的审计记录补充目标资源。
 *
 * <p>路径变量由拦截器自动取用，但指令下发、开流这类接口的目标设备在请求体里，
 * 拦截器看不到（刻意不缓存请求体：为了审计去包装每个请求的输入流，
 * 代价落在所有业务请求上）。这几处显式调用一次即可。
 */
public final class AuditContext {

    static final String RESOURCE_TYPE = AuditContext.class.getName() + ".resourceType";
    static final String RESOURCE_ID = AuditContext.class.getName() + ".resourceId";
    static final String DETAIL = AuditContext.class.getName() + ".detail";
    static final String ACTOR_USERNAME = AuditContext.class.getName() + ".actorUsername";
    static final String ACTOR_ACCOUNT_ID = AuditContext.class.getName() + ".actorAccountId";
    static final String ACTOR_TENANT_ID = AuditContext.class.getName() + ".actorTenantId";
    static final String BUSINESS_CODE = AuditContext.class.getName() + ".businessCode";

    private AuditContext() {
    }

    public static void resource(String type, String id) {
        put(RESOURCE_TYPE, type);
        put(RESOURCE_ID, id);
    }

    /** 补充一句人可读的说明。调用方 MUST NOT 传入密码、token 或密钥。 */
    public static void detail(String detail) {
        put(DETAIL, detail);
    }

    /**
     * 登录与注册这类「请求发生时还没有会话」的接口显式声明操作人，
     * 否则审计里只剩一个来源 IP，失败登录也就无从追溯。
     */
    public static void actor(String username, Long accountId, Long tenantId) {
        put(ACTOR_USERNAME, username);
        putValue(ACTOR_ACCOUNT_ID, accountId);
        putValue(ACTOR_TENANT_ID, tenantId);
    }

    /**
     * 记录本次请求的业务结果码。
     *
     * <p>控制台的业务失败走 HTTP 200 + 错误码（既有约定），审计拦截器只看状态码会把
     * 「越权被拒」记成成功——那样按结果筛选就再也找不出越权尝试，审计等于白记。
     */
    public static void businessCode(String code) {
        put(BUSINESS_CODE, code);
    }

    private static void put(String key, String value) {
        putValue(key, value);
    }

    private static void putValue(String key, Object value) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null && value != null) {
            attributes.setAttribute(key, value, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
