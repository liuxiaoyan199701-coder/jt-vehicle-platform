package io.github.jtplatform.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jt.auth.stream")
public class MediaAuthenticationProperties {
    private Mode mode = Mode.DISABLED;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public boolean isEnabled() {
        return mode == Mode.JWT;
    }

    public void validate() {
        if (mode == null) {
            throw new IllegalStateException("jt.auth.stream.mode must be DISABLED or JWT");
        }
    }

    public enum Mode {
        DISABLED,
        JWT
    }
}
