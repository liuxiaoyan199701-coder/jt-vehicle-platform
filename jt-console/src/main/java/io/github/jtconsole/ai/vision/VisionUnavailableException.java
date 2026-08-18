package io.github.jtconsole.ai.vision;

/**
 * 视觉识别不可用。
 *
 * <p>刻意是运行时异常且携带真实原因：调用方（AI 工具、上传接口）要把原因如实回告，
 * 让模型能说出「图片识别服务暂时不可用」而不是编一段并没有看过的描述，也让部署方
 * 能从日志里分清是密钥错了、超时了还是上游限流。
 */
public class VisionUnavailableException extends RuntimeException {

    public VisionUnavailableException(String message) {
        super(message);
    }

    public VisionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
