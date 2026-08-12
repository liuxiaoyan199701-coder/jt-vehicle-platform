package io.github.jtplatform.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class Rs256JwtVerifier {
    private final RsaKeyProvider keys;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration clockSkew;

    public Rs256JwtVerifier(RsaKeyProvider keys) {
        this(keys, JsonMapper.builder().build(), Clock.systemUTC(), Duration.ofSeconds(60));
    }

    Rs256JwtVerifier(RsaKeyProvider keys, ObjectMapper mapper, Clock clock, Duration clockSkew) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew");
    }

    public StreamPrincipal verify(String token) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 3) {
                throw new JwtVerificationException("Malformed JWT");
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            JsonNode header = mapper.readTree(decoder.decode(parts[0]));
            JsonNode payload = mapper.readTree(decoder.decode(parts[1]));
            if (!"RS256".equals(requiredText(header, "alg"))) {
                throw new JwtVerificationException("JWT algorithm must be RS256");
            }
            RSAPublicKey key = keys.resolve(requiredText(header, "kid"));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + '.' + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decoder.decode(parts[2]))) {
                throw new JwtVerificationException("JWT signature is invalid");
            }

            Instant issuedAt = Instant.ofEpochSecond(requiredLong(payload, "iat"));
            Instant expiresAt = Instant.ofEpochSecond(requiredLong(payload, "exp"));
            Instant now = clock.instant();
            if (!now.minus(clockSkew).isBefore(expiresAt)) {
                throw new JwtVerificationException("JWT has expired");
            }
            if (issuedAt.isAfter(now.plus(clockSkew))) {
                throw new JwtVerificationException("JWT was issued in the future");
            }
            return new StreamPrincipal(requiredText(payload, "sub"), requiredText(payload, "jti"),
                    issuedAt, expiresAt);
        } catch (JwtVerificationException exception) {
            throw exception;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new JwtVerificationException("Unable to verify JWT", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new JwtVerificationException("JWT claim is missing: " + field);
        }
        return value.asText();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new JwtVerificationException("JWT numeric claim is missing: " + field);
        }
        return value.longValue();
    }
}
