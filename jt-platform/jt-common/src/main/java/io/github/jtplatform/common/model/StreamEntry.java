package io.github.jtplatform.common.model;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class StreamEntry {
    private final StreamKey streamKey;
    private final String streamId;
    private final String mediaInstanceId;
    private final MediaTarget mediaTarget;
    private final Instant createdAt;
    private final Clock clock;
    private final AtomicReference<StreamState> state = new AtomicReference<>(StreamState.PENDING);
    private final AtomicReference<Instant> lastActiveAt;
    private final AtomicInteger subscriberCount = new AtomicInteger();
    private final CompletableFuture<StreamEntry> liveFuture = new CompletableFuture<>();
    private volatile String terminalReason;

    public StreamEntry(
            StreamKey streamKey,
            String streamId,
            String mediaInstanceId,
            MediaTarget mediaTarget,
            Clock clock) {
        this.streamKey = Objects.requireNonNull(streamKey, "streamKey");
        this.streamId = requireText(streamId, "streamId");
        this.mediaInstanceId = requireText(mediaInstanceId, "mediaInstanceId");
        this.mediaTarget = Objects.requireNonNull(mediaTarget, "mediaTarget");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.createdAt = clock.instant();
        this.lastActiveAt = new AtomicReference<>(createdAt);
    }

    public StreamKey streamKey() {
        return streamKey;
    }

    public String streamId() {
        return streamId;
    }

    public String mediaInstanceId() {
        return mediaInstanceId;
    }

    public MediaTarget mediaTarget() {
        return mediaTarget;
    }

    public StreamState state() {
        return state.get();
    }

    public int subscriberCount() {
        return subscriberCount.get();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastActiveAt() {
        return lastActiveAt.get();
    }

    public String terminalReason() {
        return terminalReason;
    }

    public CompletableFuture<StreamEntry> whenLive() {
        return liveFuture;
    }

    public boolean markLive() {
        boolean changed = state.compareAndSet(StreamState.PENDING, StreamState.LIVE);
        if (changed) {
            touch();
            liveFuture.complete(this);
        }
        return changed;
    }

    public boolean markDead(String reason) {
        StreamState previous = state.getAndSet(StreamState.DEAD);
        if (previous == StreamState.DEAD) {
            return false;
        }
        terminalReason = requireText(reason, "reason");
        touch();
        liveFuture.completeExceptionally(new StreamUnavailableException(streamKey, terminalReason));
        return true;
    }

    public int subscribe() {
        if (state.get() == StreamState.DEAD) {
            throw new StreamUnavailableException(streamKey, terminalReason == null ? "stream is dead" : terminalReason);
        }
        touch();
        return subscriberCount.incrementAndGet();
    }

    public int unsubscribe() {
        touch();
        return subscriberCount.updateAndGet(current -> Math.max(0, current - 1));
    }

    public void touch() {
        lastActiveAt.set(clock.instant());
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }
}
