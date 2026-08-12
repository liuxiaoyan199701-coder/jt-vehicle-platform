package io.github.jtplatform.common.port;

public final class StreamCommandException extends RuntimeException {
    public StreamCommandException(String message) {
        super(message);
    }

    public StreamCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
