package io.github.jtconsole.iam;

/**
 * 可稳定映射为控制台业务码的身份、租户与组织领域错误。
 *
 * <p>{@link #notFound} 同时承担「资源不存在」与「越权访问」两种语义——
 * 用 403 区分二者等于告诉租户「这个标识存在，只是不属于你」。
 */
public class IamException extends RuntimeException {

    private final String code;

    private IamException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static IamException invalid(String message) {
        return new IamException("4000", message);
    }

    /** 不存在，或存在但不在调用者数据范围内。两者对外必须完全一致。 */
    public static IamException notFound(String message) {
        return new IamException("4004", message);
    }

    public static IamException conflict(String message) {
        return new IamException("4009", message);
    }

    /** 配额或有效期限制。单独一个码，便于前端引导去升级套餐而不是改输入。 */
    public static IamException quotaExceeded(String message) {
        return new IamException("4029", message);
    }

    public String code() {
        return code;
    }
}
