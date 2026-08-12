package io.github.jtconsole.config;

import io.github.jtconsole.live.LiveBroadcaster;
import io.github.jtconsole.live.LiveWebSocketHandshakeInterceptor;
import java.util.Objects;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveBroadcaster liveBroadcaster;
    private final LiveWebSocketHandshakeInterceptor handshakeInterceptor;
    private final ConsoleProperties properties;

    public WebSocketConfig(
            LiveBroadcaster liveBroadcaster,
            LiveWebSocketHandshakeInterceptor handshakeInterceptor,
            ConsoleProperties properties) {
        this.liveBroadcaster = liveBroadcaster;
        this.handshakeInterceptor = handshakeInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] allowedOrigins = properties.getSecurity().getAllowedOrigins().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        registry.addHandler(liveBroadcaster, "/ws/live")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(allowedOrigins);
    }
}
