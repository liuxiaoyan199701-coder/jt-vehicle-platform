package io.github.jtplatform.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class Rs256JwtVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static KeyPair keys;
    private static ObjectMapper mapper;

    @BeforeAll
    static void createKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        mapper = JsonMapper.builder().build();
    }

    @Test
    void verifiesRequiredClaimsWithoutExternalAuthorizationLookup() throws Exception {
        Rs256JwtVerifier verifier = verifier();
        String jwt = jwt(NOW.minusSeconds(5), NOW.plusSeconds(120), keys);
        StreamPrincipal principal = verifier.verify(jwt);
        assertEquals("user-1", principal.subject());
        assertEquals("token-1", principal.tokenId());
    }

    @Test
    void rejectsMissingExpiredAndForgedCredentials() throws Exception {
        Rs256JwtVerifier verifier = verifier();
        assertThrows(JwtVerificationException.class, () -> verifier.verify(null));
        assertThrows(JwtVerificationException.class,
                () -> verifier.verify(jwt(NOW.minusSeconds(120), NOW.minusSeconds(61), keys)));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        assertThrows(JwtVerificationException.class,
                () -> verifier.verify(jwt(NOW.minusSeconds(5), NOW.plusSeconds(120), generator.generateKeyPair())));
    }

    private static Rs256JwtVerifier verifier() {
        return new Rs256JwtVerifier(new StaticRsaKeyProvider("key-1", (RSAPublicKey) keys.getPublic()), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60));
    }

    private static String jwt(Instant issuedAt, Instant expiresAt, KeyPair signingKeys) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(mapper.writeValueAsBytes(
                java.util.Map.of("alg", "RS256", "typ", "JWT", "kid", "key-1")));
        String payload = encoder.encodeToString(mapper.writeValueAsBytes(java.util.Map.of(
                "sub", "user-1",
                "jti", "token-1",
                "iat", issuedAt.getEpochSecond(),
                "exp", expiresAt.getEpochSecond())));
        String signingInput = header + '.' + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKeys.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + '.' + encoder.encodeToString(signature.sign());
    }
}
