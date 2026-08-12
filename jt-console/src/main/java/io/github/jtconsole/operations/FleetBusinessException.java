package io.github.jtconsole.operations;

/** 可稳定映射为控制台业务码的车队领域错误。 */
public class FleetBusinessException extends RuntimeException {

    private final String code;

    private FleetBusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static FleetBusinessException invalid(String message) {
        return new FleetBusinessException("4000", message);
    }

    public static FleetBusinessException notFound(String message) {
        return new FleetBusinessException("4004", message);
    }

    public static FleetBusinessException conflict(String message) {
        return new FleetBusinessException("4009", message);
    }

    public String code() {
        return code;
    }
}
