package io.github.jtplatform.media.config;

import io.github.jtplatform.common.model.MediaPorts;
import java.util.List;
import java.util.Objects;
import org.springframework.core.env.Environment;

public final class LegacyMediaPortValidator {
    private static final List<String> DEPRECATED_KEYS = List.of(
            "jt1078.server.tcp.main-port",
            "jt1078.server.tcp.sub-port",
            "jt1078.server.tcp.playback-port",
            "jt1078.server.tcp.talkback-port",
            "jt1078.server.websocket.port",
            "jt.media.server.main-port",
            "jt.media.server.sub-port",
            "jt.media.server.playback-port",
            "jt.media.server.talkback-port",
            "jt.media.server.websocket-port",
            "jt.media.server.management-port");

    private final Environment environment;

    public LegacyMediaPortValidator(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public MediaPorts validateAndResolve(int instanceNumber) {
        for (String key : DEPRECATED_KEYS) {
            if (environment.containsProperty(key)) {
                throw new IllegalStateException("Deprecated media port setting '" + key
                        + "' is not supported. Remove explicit media ports and configure only "
                        + "jt.instance.number; ports are derived as 78N0-78N6.");
            }
        }
        return MediaPorts.forInstance(instanceNumber);
    }
}
