package io.github.jtplatform.signal.auth;

public final class DeviceInformationUnavailableException extends RuntimeException {
    public DeviceInformationUnavailableException(String message) {
        super(message);
    }

    public DeviceInformationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
