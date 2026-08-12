package io.github.jtplatform.api.auth;

import java.time.Instant;

public record StreamPrincipal(String subject, String tokenId, Instant issuedAt, Instant expiresAt) {}
