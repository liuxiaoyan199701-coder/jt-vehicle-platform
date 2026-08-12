package io.github.jtplatform.common.port;

import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamState;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class InMemoryStreamRegistry implements StreamRegistry {
    private final ConcurrentHashMap<StreamKey, StreamEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Registration getOrRegister(StreamKey streamKey, Supplier<StreamEntry> factory) {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(factory, "factory");
        AtomicBoolean created = new AtomicBoolean();
        StreamEntry entry = entries.compute(streamKey, (key, current) -> {
            if (current != null && current.state() != StreamState.DEAD) {
                return current;
            }
            StreamEntry replacement = Objects.requireNonNull(factory.get(), "factory result");
            if (!key.equals(replacement.streamKey())) {
                throw new IllegalArgumentException("factory returned an entry for a different StreamKey");
            }
            created.set(true);
            return replacement;
        });
        return new Registration(entry, created.get());
    }

    @Override
    public Optional<StreamEntry> find(StreamKey streamKey) {
        return Optional.ofNullable(entries.get(streamKey));
    }

    @Override
    public boolean markLive(StreamKey streamKey) {
        StreamEntry entry = entries.get(streamKey);
        return entry != null && entry.markLive();
    }

    @Override
    public boolean markDead(StreamKey streamKey, String reason) {
        StreamEntry entry = entries.get(streamKey);
        return entry != null && entry.markDead(reason);
    }

    @Override
    public int addSubscriber(StreamKey streamKey) {
        return required(streamKey).subscribe();
    }

    @Override
    public int removeSubscriber(StreamKey streamKey) {
        return required(streamKey).unsubscribe();
    }

    @Override
    public List<StreamKey> expirePendingBefore(Instant cutoff, String reason) {
        Objects.requireNonNull(cutoff, "cutoff");
        return entries.values().stream()
                .filter(entry -> entry.state() == StreamState.PENDING && entry.createdAt().isBefore(cutoff))
                .filter(entry -> entry.markDead(reason))
                .map(StreamEntry::streamKey)
                .toList();
    }

    @Override
    public int invalidateMediaInstance(String instanceId, String reason) {
        Objects.requireNonNull(instanceId, "instanceId");
        int invalidated = 0;
        for (StreamEntry entry : entries.values()) {
            if (instanceId.equals(entry.mediaInstanceId()) && entry.markDead(reason)) {
                invalidated++;
            }
        }
        return invalidated;
    }

    @Override
    public Collection<StreamEntry> entries() {
        return List.copyOf(entries.values());
    }

    private StreamEntry required(StreamKey key) {
        StreamEntry entry = entries.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("No stream is registered for " + key.externalId());
        }
        return entry;
    }
}
