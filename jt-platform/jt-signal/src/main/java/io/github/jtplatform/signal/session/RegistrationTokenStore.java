package io.github.jtplatform.signal.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.yzh.web.model.entity.DeviceDO;

public final class RegistrationTokenStore {
    private final Cache<String, DeviceDO> tokens;

    public RegistrationTokenStore() {
        this(Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofDays(30))
                .build());
    }

    RegistrationTokenStore(Cache<String, DeviceDO> tokens) {
        this.tokens = tokens;
    }

    public String issue(DeviceDO device) {
        String token = UUID.randomUUID().toString();
        tokens.put(token, new DeviceDO(device));
        return token;
    }

    public Optional<DeviceDO> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokens.getIfPresent(token)).map(DeviceDO::new);
    }
}
