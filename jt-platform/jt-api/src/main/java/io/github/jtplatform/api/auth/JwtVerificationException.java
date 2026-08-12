package io.github.jtplatform.api.auth;

public final class JwtVerificationException extends RuntimeException {
    public JwtVerificationException(String message) {
        super(message);
    }

    public JwtVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
