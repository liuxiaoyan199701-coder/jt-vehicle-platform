package io.github.jtconsole.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

public final class BearerTokenSupport {

    private static final int MAX_TOKEN_LENGTH = 512;

    private BearerTokenSupport() {
    }

    public static Optional<String> extract(HttpServletRequest request) {
        return parse(request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    public static Optional<String> parse(String authorization) {
        if (authorization == null || authorization.length() <= 7) {
            return Optional.empty();
        }
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = authorization.substring(7);
        if (token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < token.length(); i++) {
            if (Character.isWhitespace(token.charAt(i))) {
                return Optional.empty();
            }
        }
        return Optional.of(token);
    }
}
