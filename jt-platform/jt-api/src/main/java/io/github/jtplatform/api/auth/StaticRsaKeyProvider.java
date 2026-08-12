package io.github.jtplatform.api.auth;

import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

public final class StaticRsaKeyProvider implements RsaKeyProvider {
    private final String keyId;
    private final RSAPublicKey publicKey;

    public StaticRsaKeyProvider(String keyId, RSAPublicKey publicKey) {
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    }

    @Override
    public RSAPublicKey resolve(String requestedKeyId) {
        if (!keyId.equals(requestedKeyId)) {
            throw new JwtVerificationException("Unknown JWT key id");
        }
        return publicKey;
    }
}
