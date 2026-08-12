package io.github.jtplatform.signal.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

final class CachingRemoteDeviceInformationSource implements DeviceInformationSource {
    private final RemoteDeviceInformationClient client;
    private final Cache<String, Optional<DeviceInformation>> cache;

    CachingRemoteDeviceInformationSource(
            RemoteDeviceInformationClient client,
            Duration cacheTtl,
            long cacheMaximumSize) {
        this.client = Objects.requireNonNull(client, "client");
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("jt.auth.device.remote.cache-ttl must be positive");
        }
        if (cacheMaximumSize <= 0) {
            throw new IllegalArgumentException("jt.auth.device.remote.cache-maximum-size must be positive");
        }
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtl)
                .maximumSize(cacheMaximumSize)
                .build();
    }

    @Override
    public Optional<DeviceInformation> find(String terminalId) {
        String normalized = requireText(terminalId);
        try {
            return cache.get(normalized, key -> Objects.requireNonNull(
                    client.find(key), "remote device client result"));
        } catch (DeviceInformationUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof DeviceInformationUnavailableException unavailable) {
                throw unavailable;
            }
            throw new DeviceInformationUnavailableException("remote device query failed", exception);
        }
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("terminalId must not be blank");
        }
        return value.trim();
    }
}
