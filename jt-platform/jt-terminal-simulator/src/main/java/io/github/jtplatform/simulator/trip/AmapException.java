package io.github.jtplatform.simulator.trip;

/**
 * 路径规划调用失败。
 *
 * <p>受检异常：调用点必须显式决定怎么降级。这条链路失败是**常态**而非意外——没配密钥、密钥类型
 * 选错、配额用完、断网，每一种都会经常发生，把它做成运行时异常只会让某个调用点忘了处理，
 * 于是一次网络抖动就中断整个行程。
 */
public class AmapException extends Exception {

    private static final long serialVersionUID = 1L;

    public AmapException(String message) {
        super(message);
    }

    public AmapException(String message, Throwable cause) {
        super(message, cause);
    }
}
