package io.github.jtplatform.common.port;

import io.github.jtplatform.common.model.MediaInstance;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryMediaInstanceRegistry implements MediaInstanceRegistry {
    private final ConcurrentHashMap<String, MediaInstance> instances = new ConcurrentHashMap<>();

    @Override
    public void register(MediaInstance instance) {
        Objects.requireNonNull(instance, "instance");
        instances.put(instance.instanceId(), instance);
    }

    @Override
    public Optional<MediaInstance> find(String instanceId) {
        return Optional.ofNullable(instances.get(requireText(instanceId, "instanceId")));
    }

    @Override
    public void updateLoad(String instanceId, int currentStreams, long outboundBitsPerSecond, Instant heartbeatAt) {
        String id = requireText(instanceId, "instanceId");
        Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        instances.compute(id, (key, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("Unknown media instance: " + id);
            }
            return current.withLoad(currentStreams, outboundBitsPerSecond, heartbeatAt);
        });
    }

    @Override
    public void markDraining(String instanceId, Instant heartbeatAt) {
        String id = requireText(instanceId, "instanceId");
        instances.computeIfPresent(id, (key, current) -> current.asDraining(heartbeatAt));
    }

    @Override
    public Collection<MediaInstance> activeAfter(Instant heartbeatCutoff) {
        Objects.requireNonNull(heartbeatCutoff, "heartbeatCutoff");
        return instances.values().stream()
                .filter(instance -> !instance.heartbeatAt().isBefore(heartbeatCutoff))
                .filter(instance -> !instance.draining())
                .toList();
    }

    @Override
    public List<String> removeExpiredBefore(Instant heartbeatCutoff) {
        Objects.requireNonNull(heartbeatCutoff, "heartbeatCutoff");
        return instances.entrySet().stream()
                .filter(entry -> entry.getValue().heartbeatAt().isBefore(heartbeatCutoff))
                .filter(entry -> instances.remove(entry.getKey(), entry.getValue()))
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    @Override
    public Collection<MediaInstance> all() {
        return List.copyOf(instances.values());
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }
}
