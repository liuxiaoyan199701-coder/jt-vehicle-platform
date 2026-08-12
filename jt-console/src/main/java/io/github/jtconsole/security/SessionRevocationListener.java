package io.github.jtconsole.security;

public interface SessionRevocationListener {

    void onAccessCredentialRevoked(String accessCredentialId);

    void onSessionRevoked(String authenticationSessionId);
}
