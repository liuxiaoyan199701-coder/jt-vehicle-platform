package io.github.jtplatform.simulator.signal;

import java.io.IOException;

public final class InvalidJt808FrameException extends IOException {
    public InvalidJt808FrameException(String message) {
        super(message);
    }

    public InvalidJt808FrameException(String message, Throwable cause) {
        super(message, cause);
    }
}
