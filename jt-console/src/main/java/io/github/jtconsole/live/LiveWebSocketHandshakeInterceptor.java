package io.github.jtconsole.live;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.security.SessionTokenService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public final class LiveWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String APPLICATION_PROTOCOL = "jt-console.v1";
    public static final String BEARER_PROTOCOL_PREFIX = "bearer.";
    public static final String AUTHENTICATED_SESSION_ATTRIBUTE =
            LiveWebSocketHandshakeInterceptor.class.getName() + ".authenticatedSession";

    private static final String WEBSOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol";
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveWebSocketHandshakeInterceptor.class);

    private final SessionTokenService tokens;
    private final Set<String> allowedOrigins;

    @Autowired
    public LiveWebSocketHandshakeInterceptor(SessionTokenService tokens, ConsoleProperties properties) {
        this(tokens, Set.copyOf(properties.getSecurity().getAllowedOrigins()));
    }

    LiveWebSocketHandshakeInterceptor(SessionTokenService tokens, Set<String> allowedOrigins) {
        this.tokens = tokens;
        this.allowedOrigins = allowedOrigins.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .peek(origin -> {
                    if ("*".equals(origin)) {
                        throw new IllegalArgumentException("Live WebSocket allowed origins must be explicit");
                    }
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String origin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
        if (origin == null || !allowedOrigins.contains(origin)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            LOGGER.debug("Rejected live WebSocket handshake from disallowed origin");
            return false;
        }

        List<String> protocols = request.getHeaders().getOrEmpty(WEBSOCKET_PROTOCOL_HEADER).stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        if (!protocols.contains(APPLICATION_PROTOCOL)) {
            return rejectUnauthorized(response, "missing application protocol");
        }

        List<String> credentials = protocols.stream()
                .filter(value -> value.startsWith(BEARER_PROTOCOL_PREFIX))
                .map(value -> value.substring(BEARER_PROTOCOL_PREFIX.length()))
                .filter(value -> !value.isBlank())
                .toList();
        if (credentials.size() != 1) {
            return rejectUnauthorized(response, "missing or ambiguous bearer protocol");
        }

        return tokens.validateAccessSession(credentials.getFirst())
                .map(authentication -> {
                    attributes.put(AUTHENTICATED_SESSION_ATTRIBUTE, authentication);
                    return true;
                })
                .orElseGet(() -> rejectUnauthorized(response, "invalid access token"));
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // No credential or request data is retained after validation.
    }

    private static boolean rejectUnauthorized(ServerHttpResponse response, String category) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        LOGGER.debug("Rejected live WebSocket handshake: {}", category);
        return false;
    }
}
