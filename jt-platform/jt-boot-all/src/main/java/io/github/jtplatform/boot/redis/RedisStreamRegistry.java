package io.github.jtplatform.boot.redis;

import io.github.jtplatform.boot.cluster.ClusterStateProtocol;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.model.StreamUnavailableException;
import io.github.jtplatform.common.port.StreamRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public final class RedisStreamRegistry implements StreamRegistry, AutoCloseable {
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 3;

    private final StringRedisTemplate redis;
    private final RedisRegistrySupport support;
    private final Clock clock;
    private final long pendingTtlSeconds;
    private final long statePollMillis;
    private final RedisScript<Long> getOrRegisterScript;
    private final RedisScript<Long> subscribeScript;
    private final RedisScript<Long> unsubscribeScript;
    private final RedisScript<Long> markLiveScript;
    private final RedisScript<Long> markDeadScript;
    private final ExecutorService stateWatchers = Executors.newVirtualThreadPerTaskExecutor();

    public RedisStreamRegistry(
            StringRedisTemplate redis,
            RedisRegistrySupport support,
            Clock clock,
            Duration pendingTimeout,
            Duration statePollInterval) {
        this.redis = redis;
        this.support = support;
        this.clock = clock;
        this.pendingTtlSeconds = positiveSeconds(pendingTimeout, "pendingTimeout");
        this.statePollMillis = positiveMillis(statePollInterval, "statePollInterval");
        this.getOrRegisterScript = support.longScript("/redis/stream-get-or-register.lua");
        this.subscribeScript = support.longScript("/redis/stream-subscribe.lua");
        this.unsubscribeScript = support.longScript("/redis/stream-unsubscribe.lua");
        this.markLiveScript = support.longScript("/redis/stream-mark-live.lua");
        this.markDeadScript = support.longScript("/redis/stream-mark-dead.lua");
    }

    @Override
    public Registration getOrRegister(StreamKey streamKey, Supplier<StreamEntry> factory) {
        Optional<StreamEntry> existing = find(streamKey);
        if (existing.isPresent() && existing.get().state() != StreamState.DEAD) {
            return new Registration(existing.get(), false);
        }
        StreamEntry candidate = factory.get();
        String payload = support.write(ClusterStateProtocol.StreamEntryData.from(candidate));
        Long created = redis.execute(getOrRegisterScript,
                List.of(support.streamKey(streamKey.externalId()), support.streamIndexKey()),
                streamKey.externalId(), payload, String.valueOf(pendingTtlSeconds));
        if (created != null && created == 1L) {
            return new Registration(candidate, true);
        }
        StreamEntry winner = find(streamKey)
                .orElseThrow(() -> new IllegalStateException("Stream vanished after registration race"));
        return new Registration(winner, false);
    }

    @Override
    public Optional<StreamEntry> find(StreamKey streamKey) {
        String json = redis.opsForValue().get(support.streamKey(streamKey.externalId()));
        if (json == null) {
            return Optional.empty();
        }
        StreamEntry entry = support.read(json, ClusterStateProtocol.StreamEntryData.class).toDomain();
        watchPendingState(entry);
        return Optional.of(entry);
    }

    @Override
    public boolean markLive(StreamKey streamKey) {
        Long result = redis.execute(markLiveScript,
                List.of(support.streamKey(streamKey.externalId())), clock.instant().toString());
        return result != null && result == 1L;
    }

    @Override
    public boolean markDead(StreamKey streamKey, String reason) {
        Long result = redis.execute(markDeadScript,
                List.of(support.streamKey(streamKey.externalId())), reason, clock.instant().toString());
        return result != null && result == 1L;
    }

    @Override
    public int addSubscriber(StreamKey streamKey) {
        Long result = redis.execute(subscribeScript,
                List.of(support.streamKey(streamKey.externalId())), clock.instant().toString());
        if (result == null || result == -1L) {
            throw new IllegalArgumentException("No stream is registered for " + streamKey.externalId());
        }
        if (result == -2L) {
            throw new StreamUnavailableException(streamKey, "stream is dead");
        }
        return result.intValue();
    }

    @Override
    public int removeSubscriber(StreamKey streamKey) {
        Long result = redis.execute(unsubscribeScript,
                List.of(support.streamKey(streamKey.externalId())), clock.instant().toString());
        if (result == null || result == -1L) {
            throw new IllegalArgumentException("No stream is registered for " + streamKey.externalId());
        }
        return result.intValue();
    }

    @Override
    public List<StreamKey> expirePendingBefore(Instant cutoff, String reason) {
        List<StreamKey> expired = new ArrayList<>();
        Set<String> members = redis.opsForSet().members(support.streamIndexKey());
        if (members == null) {
            return expired;
        }
        for (String externalId : members) {
            String json = redis.opsForValue().get(support.streamKey(externalId));
            if (json == null) {
                continue;
            }
            ClusterStateProtocol.StreamEntryData data =
                    support.read(json, ClusterStateProtocol.StreamEntryData.class);
            if (!StreamState.PENDING.name().equals(data.state())
                    || !Instant.parse(data.createdAt()).isBefore(cutoff)) {
                continue;
            }
            if (markDead(data.streamKey().toDomain(), reason)) {
                expired.add(data.streamKey().toDomain());
            }
        }
        return expired;
    }

    @Override
    public int invalidateMediaInstance(String instanceId, String reason) {
        int invalidated = 0;
        Set<String> members = redis.opsForSet().members(support.streamIndexKey());
        if (members == null) {
            return 0;
        }
        for (String externalId : members) {
            String json = redis.opsForValue().get(support.streamKey(externalId));
            if (json == null) {
                continue;
            }
            ClusterStateProtocol.StreamEntryData data =
                    support.read(json, ClusterStateProtocol.StreamEntryData.class);
            if (instanceId.equals(data.mediaInstanceId())
                    && markDead(data.streamKey().toDomain(), reason)) {
                invalidated++;
            }
        }
        return invalidated;
    }

    @Override
    public Collection<StreamEntry> entries() {
        List<StreamEntry> entries = new ArrayList<>();
        Set<String> members = redis.opsForSet().members(support.streamIndexKey());
        if (members == null) {
            return entries;
        }
        for (String externalId : members) {
            String json = redis.opsForValue().get(support.streamKey(externalId));
            if (json != null) {
                entries.add(support.read(json, ClusterStateProtocol.StreamEntryData.class).toDomain());
            }
        }
        return entries;
    }

    private void watchPendingState(StreamEntry localEntry) {
        if (localEntry.state() != StreamState.PENDING) {
            return;
        }
        stateWatchers.submit(() -> pollUntilTerminal(localEntry));
    }

    private void pollUntilTerminal(StreamEntry localEntry) {
        int consecutiveFailures = 0;
        while (!Thread.currentThread().isInterrupted() && localEntry.state() == StreamState.PENDING) {
            try {
                TimeUnit.MILLISECONDS.sleep(statePollMillis);
                String json = redis.opsForValue().get(support.streamKey(localEntry.streamKey().externalId()));
                consecutiveFailures = 0;
                if (json == null) {
                    localEntry.markDead("STREAM_NOT_FOUND");
                } else {
                    ClusterStateProtocol.StreamEntryData remote =
                            support.read(json, ClusterStateProtocol.StreamEntryData.class);
                    StreamState remoteState = StreamState.valueOf(remote.state());
                    if (remoteState == StreamState.LIVE) {
                        localEntry.markLive();
                    } else if (remoteState == StreamState.DEAD) {
                        localEntry.markDead(remote.terminalReason() == null || remote.terminalReason().isBlank()
                                ? "stream is dead"
                                : remote.terminalReason());
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException pollingFailure) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                    localEntry.markDead("REDIS_STATE_UNAVAILABLE");
                }
            }
        }
    }

    @Override
    public void close() {
        stateWatchers.shutdownNow();
    }

    private static long positiveSeconds(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return Math.max(1, duration.toSeconds());
    }

    private static long positiveMillis(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return Math.max(1, duration.toMillis());
    }
}
