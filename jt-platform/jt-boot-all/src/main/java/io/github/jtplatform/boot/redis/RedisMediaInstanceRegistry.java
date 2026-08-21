package io.github.jtplatform.boot.redis;

import io.github.jtplatform.boot.cluster.ClusterStateProtocol;
import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisMediaInstanceRegistry implements MediaInstanceRegistry {
    private final StringRedisTemplate redis;
    private final RedisRegistrySupport support;
    private final Duration heartbeatTtl;

    public RedisMediaInstanceRegistry(
            StringRedisTemplate redis,
            RedisRegistrySupport support,
            Duration heartbeatTtl) {
        this.redis = redis;
        this.support = support;
        this.heartbeatTtl = requirePositive(heartbeatTtl, "heartbeatTtl");
    }

    @Override
    public void register(MediaInstance instance) {
        String json = support.write(ClusterStateProtocol.MediaInstanceData.from(instance));
        redis.opsForValue().set(support.instanceKey(instance.instanceId()), json, heartbeatTtl);
        redis.opsForSet().add(support.instanceIndexKey(), instance.instanceId());
    }

    @Override
    public Optional<MediaInstance> find(String instanceId) {
        String json = redis.opsForValue().get(support.instanceKey(instanceId));
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(support.read(json, ClusterStateProtocol.MediaInstanceData.class).toDomain());
    }

    @Override
    public void updateLoad(String instanceId, int currentStreams, long outboundBitsPerSecond, Instant heartbeatAt) {
        String key = support.instanceKey(instanceId);
        String json = redis.opsForValue().get(key);
        if (json == null) {
            throw new IllegalArgumentException("Unknown media instance: " + instanceId);
        }
        MediaInstance current = support.read(json, ClusterStateProtocol.MediaInstanceData.class).toDomain();
        MediaInstance updated = current.withLoad(currentStreams, outboundBitsPerSecond, heartbeatAt);
        redis.opsForValue().set(key, support.write(ClusterStateProtocol.MediaInstanceData.from(updated)), heartbeatTtl);
    }

    @Override
    public void markDraining(String instanceId, Instant heartbeatAt) {
        String key = support.instanceKey(instanceId);
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return;
        }
        MediaInstance current = support.read(json, ClusterStateProtocol.MediaInstanceData.class).toDomain();
        MediaInstance updated = current.asDraining(heartbeatAt);
        redis.opsForValue().set(key, support.write(ClusterStateProtocol.MediaInstanceData.from(updated)), heartbeatTtl);
    }

    @Override
    public Collection<MediaInstance> activeAfter(Instant heartbeatCutoff) {
        List<MediaInstance> result = new ArrayList<>();
        Set<String> ids = redis.opsForSet().members(support.instanceIndexKey());
        if (ids == null) {
            return result;
        }
        for (String id : ids) {
            find(id).ifPresent(instance -> {
                if (!instance.heartbeatAt().isBefore(heartbeatCutoff) && !instance.draining()) {
                    result.add(instance);
                }
            });
        }
        return result;
    }

    @Override
    public List<String> removeExpiredBefore(Instant heartbeatCutoff) {
        List<String> removed = new ArrayList<>();
        Set<String> ids = redis.opsForSet().members(support.instanceIndexKey());
        if (ids == null) {
            return removed;
        }
        for (String id : ids) {
            Optional<MediaInstance> instance = find(id);
            if (instance.isEmpty() || instance.get().heartbeatAt().isBefore(heartbeatCutoff)) {
                redis.delete(support.instanceKey(id));
                redis.opsForSet().remove(support.instanceIndexKey(), id);
                removed.add(id);
            }
        }
        return removed;
    }

    @Override
    public Collection<MediaInstance> all() {
        List<MediaInstance> result = new ArrayList<>();
        Set<String> ids = redis.opsForSet().members(support.instanceIndexKey());
        if (ids == null) {
            return result;
        }
        for (String id : ids) {
            find(id).ifPresent(result::add);
        }
        return result;
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
