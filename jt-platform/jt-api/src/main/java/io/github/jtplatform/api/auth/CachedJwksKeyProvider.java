package io.github.jtplatform.api.auth;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class CachedJwksKeyProvider implements RsaKeyProvider {
    private final URI jwksUri;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration cacheTtl;
    private volatile Cache cache = new Cache(Map.of(), Instant.EPOCH);

    public CachedJwksKeyProvider(URI jwksUri, Duration cacheTtl) {
        this(jwksUri, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                JsonMapper.builder().build(), Clock.systemUTC(), cacheTtl);
    }

    CachedJwksKeyProvider(
            URI jwksUri,
            HttpClient client,
            ObjectMapper mapper,
            Clock clock,
            Duration cacheTtl) {
        this.jwksUri = Objects.requireNonNull(jwksUri, "jwksUri");
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
    }

    @Override
    public RSAPublicKey resolve(String keyId) {
        String id = requireText(keyId, "JWT key id");
        Cache current = cache;
        if (!clock.instant().isBefore(current.loadedAt().plus(cacheTtl)) || !current.keys().containsKey(id)) {
            current = refresh();
        }
        RSAPublicKey key = current.keys().get(id);
        if (key == null) {
            throw new JwtVerificationException("Unknown JWT key id");
        }
        return key;
    }

    public synchronized Cache refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(jwksUri).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new JwtVerificationException("JWKS endpoint returned HTTP " + response.statusCode());
            }
            Cache loaded = new Cache(parseKeys(mapper.readTree(response.body())), clock.instant());
            cache = loaded;
            return loaded;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JwtVerificationException("JWKS refresh interrupted", exception);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof JwtVerificationException verificationException) {
                throw verificationException;
            }
            throw new JwtVerificationException("Unable to refresh JWKS", exception);
        }
    }

    private static Map<String, RSAPublicKey> parseKeys(JsonNode document) {
        JsonNode keysNode = document.get("keys");
        if (keysNode == null || !keysNode.isArray()) {
            throw new JwtVerificationException("JWKS document has no keys array");
        }
        Map<String, RSAPublicKey> result = new HashMap<>();
        for (JsonNode key : keysNode) {
            if (!"RSA".equals(requiredText(key, "kty"))) {
                continue;
            }
            String algorithm = key.get("alg") == null ? "RS256" : key.get("alg").asText();
            if (!"RS256".equals(algorithm)) {
                continue;
            }
            result.put(requiredText(key, "kid"), rsaKey(requiredText(key, "n"), requiredText(key, "e")));
        }
        if (result.isEmpty()) {
            throw new JwtVerificationException("JWKS contains no RS256 keys");
        }
        return Map.copyOf(result);
    }

    private static RSAPublicKey rsaKey(String modulus, String exponent) {
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            RSAPublicKeySpec spec = new RSAPublicKeySpec(
                    new BigInteger(1, decoder.decode(modulus)),
                    new BigInteger(1, decoder.decode(exponent)));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException exception) {
            throw new JwtVerificationException("Invalid RSA key in JWKS", exception);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? requireText(null, field) : requireText(value.asText(), field);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new JwtVerificationException(name + " is required");
        }
        return value.trim();
    }

    public record Cache(Map<String, RSAPublicKey> keys, Instant loadedAt) {}
}
