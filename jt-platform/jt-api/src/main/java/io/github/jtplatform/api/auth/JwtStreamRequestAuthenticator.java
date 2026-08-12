package io.github.jtplatform.api.auth;

import java.util.Objects;

public final class JwtStreamRequestAuthenticator implements StreamRequestAuthenticator {
    private static final String BEARER_PREFIX = "Bearer ";
    private final Rs256JwtVerifier verifier;

    public JwtStreamRequestAuthenticator(Rs256JwtVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    public StreamPrincipal authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new JwtVerificationException("Bearer token is required");
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new JwtVerificationException("Bearer token is required");
        }
        return verifier.verify(token);
    }
}
