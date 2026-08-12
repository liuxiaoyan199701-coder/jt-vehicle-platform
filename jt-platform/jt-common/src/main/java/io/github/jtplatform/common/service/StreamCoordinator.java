package io.github.jtplatform.common.service;

import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.model.StreamTicket;
import io.github.jtplatform.common.port.StreamCommandPort;
import io.github.jtplatform.common.port.StreamRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class StreamCoordinator {
    private final StreamRegistry streams;
    private final MediaScheduler scheduler;
    private final StreamCommandPort commands;
    private final ScheduledExecutorService executor;
    private final Clock clock;
    private final Duration idleTimeout;
    private final Duration pendingTimeout;
    private final Supplier<String> streamIdSupplier;
    private final ConcurrentHashMap<StreamKey, ScheduledFuture<?>> closeTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<StreamKey, ScheduledFuture<?>> pendingTasks = new ConcurrentHashMap<>();
    private final java.util.Set<StreamKey> commandlessStreams = ConcurrentHashMap.newKeySet();

    public StreamCoordinator(
            StreamRegistry streams,
            MediaScheduler scheduler,
            StreamCommandPort commands,
            ScheduledExecutorService executor,
            Clock clock,
            Duration idleTimeout,
            Duration pendingTimeout) {
        this(streams, scheduler, commands, executor, clock, idleTimeout, pendingTimeout,
                () -> UUID.randomUUID().toString());
    }

    public StreamCoordinator(
            StreamRegistry streams,
            MediaScheduler scheduler,
            StreamCommandPort commands,
            ScheduledExecutorService executor,
            Clock clock,
            Duration idleTimeout,
            Duration pendingTimeout,
            Supplier<String> streamIdSupplier) {
        this.streams = Objects.requireNonNull(streams, "streams");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idleTimeout = requirePositive(idleTimeout, "idleTimeout");
        this.pendingTimeout = requirePositive(pendingTimeout, "pendingTimeout");
        this.streamIdSupplier = Objects.requireNonNull(streamIdSupplier, "streamIdSupplier");
    }

    public StreamTicket open(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        if (streamKey.streamKind() == StreamKind.PLAYBACK) {
            throw new IllegalArgumentException("Playback requires an explicit recording time range");
        }
        return open(streamKey, target -> commands.openLive(streamKey, target));
    }

    public StreamTicket openPlayback(
            StreamKey streamKey,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(startTime, "startTime");
        if (streamKey.streamKind() != StreamKind.PLAYBACK) {
            throw new IllegalArgumentException("streamKind must be playback");
        }
        if (endTime != null && endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("endTime must not be before startTime");
        }
        return open(streamKey, target -> commands.openPlayback(streamKey, target, startTime, endTime));
    }

    public StreamTicket openLocalPlayback(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        if (streamKey.streamKind() != StreamKind.PLAYBACK) {
            throw new IllegalArgumentException("streamKind must be playback");
        }
        cancelDelayedClose(streamKey);
        StreamRegistry.Registration registration = streams.getOrRegister(streamKey, () -> {
            MediaInstance instance = scheduler.pick(streamKey);
            return new StreamEntry(streamKey, streamIdSupplier.get(), instance.instanceId(),
                    instance.targetFor(streamKey.streamKind()), clock);
        });
        streams.addSubscriber(streamKey);
        if (registration.created()) {
            commandlessStreams.add(streamKey);
            streams.markLive(streamKey);
        }
        return StreamTicket.from(registration.entry());
    }

    private StreamTicket open(StreamKey streamKey, Consumer<MediaTarget> command) {
        cancelDelayedClose(streamKey);
        StreamRegistry.Registration registration = streams.getOrRegister(streamKey, () -> {
            MediaInstance instance = scheduler.pick(streamKey);
            return new StreamEntry(streamKey, streamIdSupplier.get(), instance.instanceId(),
                    instance.targetFor(streamKey.streamKind()), clock);
        });
        streams.addSubscriber(streamKey);

        if (registration.created()) {
            try {
                commandlessStreams.remove(streamKey);
                command.accept(registration.entry().mediaTarget());
                pendingTasks.put(streamKey, executor.schedule(
                        () -> expirePending(streamKey), pendingTimeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (RuntimeException exception) {
                cancelPendingTimeout(streamKey);
                streams.removeSubscriber(streamKey);
                streams.markDead(streamKey, "signal command failed");
                throw exception;
            }
        }
        return StreamTicket.from(registration.entry());
    }

    public CompletableFuture<StreamEntry> awaitLive(StreamKey streamKey) {
        return streams.find(streamKey)
                .map(StreamEntry::whenLive)
                .orElseGet(() -> CompletableFuture.failedFuture(
                        new IllegalArgumentException("Stream is not registered: " + streamKey.externalId())));
    }

    public boolean onFirstPacket(StreamKey streamKey, String mediaInstanceId) {
        StreamEntry entry = streams.find(streamKey).orElse(null);
        if (entry == null || !entry.mediaInstanceId().equals(mediaInstanceId)) {
            return false;
        }
        boolean accepted = streams.markLive(streamKey) || entry.state() == StreamState.LIVE;
        if (accepted) {
            cancelPendingTimeout(streamKey);
        }
        return accepted;
    }

    public int release(StreamKey streamKey) {
        int remaining = streams.removeSubscriber(streamKey);
        if (remaining == 0) {
            closeTasks.compute(streamKey, (key, previous) -> {
                if (previous != null) {
                    previous.cancel(false);
                }
                return executor.schedule(() -> closeIfIdle(key), idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
            });
        }
        return remaining;
    }

    public void closeNow(StreamKey streamKey, String reason) {
        cancelDelayedClose(streamKey);
        cancelPendingTimeout(streamKey);
        boolean commandless = commandlessStreams.remove(streamKey);
        StreamEntry entry = streams.find(streamKey).orElse(null);
        if (entry == null || entry.state() == StreamState.DEAD) {
            return;
        }
        try {
            if (!commandless) {
                commands.close(streamKey);
            }
        } finally {
            streams.markDead(streamKey, reason);
        }
    }

    private void expirePending(StreamKey streamKey) {
        pendingTasks.remove(streamKey);
        streams.find(streamKey)
                .filter(entry -> entry.state() == StreamState.PENDING)
                .ifPresent(entry -> streams.markDead(streamKey, "DEVICE_NO_RESPONSE"));
    }

    private void closeIfIdle(StreamKey streamKey) {
        closeTasks.remove(streamKey);
        streams.find(streamKey)
                .filter(entry -> entry.subscriberCount() == 0)
                .filter(entry -> entry.state() != StreamState.DEAD)
                .ifPresent(entry -> closeNow(streamKey, "idle timeout"));
    }

    private void cancelDelayedClose(StreamKey streamKey) {
        ScheduledFuture<?> future = closeTasks.remove(streamKey);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void cancelPendingTimeout(StreamKey streamKey) {
        ScheduledFuture<?> future = pendingTasks.remove(streamKey);
        if (future != null) {
            future.cancel(false);
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
