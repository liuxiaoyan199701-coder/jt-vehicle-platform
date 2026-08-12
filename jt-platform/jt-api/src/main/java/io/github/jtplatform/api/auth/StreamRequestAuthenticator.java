package io.github.jtplatform.api.auth;

public interface StreamRequestAuthenticator {
    StreamPrincipal authenticate(String authorizationHeader);
}
