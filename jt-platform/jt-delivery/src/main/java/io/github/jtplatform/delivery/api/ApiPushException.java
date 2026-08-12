package io.github.jtplatform.delivery.api;

public final class ApiPushException extends RuntimeException {
    private final int statusCode;

    public ApiPushException(int statusCode) {
        super("API push returned HTTP status " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
