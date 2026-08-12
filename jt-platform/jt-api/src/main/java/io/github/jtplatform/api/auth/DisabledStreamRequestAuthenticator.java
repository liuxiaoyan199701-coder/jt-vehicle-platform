package io.github.jtplatform.api.auth;

import java.time.Instant;

public final class DisabledStreamRequestAuthenticator implements StreamRequestAuthenticator {
    @Override
    public StreamPrincipal authenticate(String authorizationHeader) {
        return new StreamPrincipal("anonymous", "disabled", Instant.EPOCH, Instant.MAX);
    }
}
