package io.github.jtplatform.api.auth;

import java.security.interfaces.RSAPublicKey;

public interface RsaKeyProvider {
    RSAPublicKey resolve(String keyId);
}
