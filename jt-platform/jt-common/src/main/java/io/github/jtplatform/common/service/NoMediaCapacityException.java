package io.github.jtplatform.common.service;

public final class NoMediaCapacityException extends RuntimeException {
    public NoMediaCapacityException() {
        super("No media instance has available capacity");
    }
}
