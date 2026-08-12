package io.github.jtplatform.boot.cluster;

import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.port.StreamRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HttpStreamRegistry implements StreamRegistry, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpStreamRegistry.class);
    private static final int MAX_CONSECUTIVE_POLL_FAILURES = 3;

    private final ClusterStateHttpClient client;
    private final long statePollMillis;
    private final ExecutorService stateWatchers = Executors.newVirtualThreadPerTaskExecutor();

    HttpStreamRegistry(ClusterStateHttpClient client, Duration statePollInterval) {
        this.client = client;
        if (statePollInterval == null || statePollInterval.isZero() || statePollInterval.isNegative()) {
            throw new IllegalArgumentException("statePollInterval must be positive");
        }
        this.statePollMillis = Math.max(1, statePollInterval.toMillis());
    }

    @Override
    public Registration getOrRegister(StreamKey streamKey, Supplier<StreamEntry> factory) {
        StreamEntry candidate = factory.get();
        var response = client.post("streams/register",
                new ClusterStateProtocol.StreamRegistrationRequest(
                        ClusterStateProtocol.StreamEntryData.from(candidate)),
                ClusterStateProtocol.StreamRegistrationResult.class);
        StreamEntry entry = response.entry().toDomain();
        watchPendingState(entry);
        return new Registration(entry, response.created());
    }

    @Override
    public Optional<StreamEntry> find(StreamKey streamKey) {
        ClusterStateProtocol.StreamEntryData snapshot = lookup(streamKey);
        if (snapshot == null) {
            return Optional.empty();
        }
        StreamEntry entry = snapshot.toDomain();
        watchPendingState(entry);
        return Optional.of(entry);
    }

    @Override
    public boolean markLive(StreamKey streamKey) {
        return client.post("streams/mark-live", keyRequest(streamKey),
                        ClusterStateProtocol.BooleanResult.class)
                .value();
    }

    @Override
    public boolean markDead(StreamKey streamKey, String reason) {
        return client.post("streams/mark-dead", new ClusterStateProtocol.StreamDeadRequest(
                        ClusterStateProtocol.StreamKeyData.from(streamKey), reason),
                        ClusterStateProtocol.BooleanResult.class)
                .value();
    }

    @Override
    public int addSubscriber(StreamKey streamKey) {
        return client.post("streams/add-subscriber", keyRequest(streamKey),
                        ClusterStateProtocol.IntResult.class)
                .value();
    }

    @Override
    public int removeSubscriber(StreamKey streamKey) {
        return client.post("streams/remove-subscriber", keyRequest(streamKey),
                        ClusterStateProtocol.IntResult.class)
                .value();
    }

    @Override
    public List<StreamKey> expirePendingBefore(Instant cutoff, String reason) {
        return client.post("streams/expire-pending",
                        new ClusterStateProtocol.PendingExpiryRequest(cutoff.toString(), reason),
                        ClusterStateProtocol.StreamKeysResult.class)
                .streamKeys().stream()
                .map(ClusterStateProtocol.StreamKeyData::toDomain)
                .toList();
    }

    @Override
    public int invalidateMediaInstance(String instanceId, String reason) {
        return client.post("streams/invalidate-instance",
                        new ClusterStateProtocol.InstanceInvalidationRequest(instanceId, reason),
                        ClusterStateProtocol.IntResult.class)
                .value();
    }

    @Override
    public Collection<StreamEntry> entries() {
        return client.post("streams/all", ClusterStateProtocol.Ack.ok(),
                        ClusterStateProtocol.StreamEntriesResult.class)
                .entries().stream()
                .map(ClusterStateProtocol.StreamEntryData::toDomain)
                .toList();
    }

    private ClusterStateProtocol.StreamEntryData lookup(StreamKey streamKey) {
        return client.post("streams/find", keyRequest(streamKey),
                        ClusterStateProtocol.StreamLookupResult.class)
                .entry();
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
                ClusterStateProtocol.StreamEntryData remote = lookup(localEntry.streamKey());
                consecutiveFailures = 0;
                if (remote == null) {
                    localEntry.markDead("STREAM_NOT_FOUND");
                } else if (StreamState.valueOf(remote.state()) == StreamState.LIVE) {
                    localEntry.markLive();
                } else if (StreamState.valueOf(remote.state()) == StreamState.DEAD) {
                    localEntry.markDead(remote.terminalReason() == null || remote.terminalReason().isBlank()
                            ? "stream is dead"
                            : remote.terminalReason());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException pollingFailure) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                    LOGGER.warn("Unable to observe cluster state for stream {}",
                            localEntry.streamKey().externalId(), pollingFailure);
                    localEntry.markDead("CLUSTER_STATE_UNAVAILABLE");
                }
            }
        }
    }

    private static ClusterStateProtocol.StreamKeyRequest keyRequest(StreamKey streamKey) {
        return new ClusterStateProtocol.StreamKeyRequest(ClusterStateProtocol.StreamKeyData.from(streamKey));
    }

    @Override
    public void close() {
        stateWatchers.shutdownNow();
    }
}
