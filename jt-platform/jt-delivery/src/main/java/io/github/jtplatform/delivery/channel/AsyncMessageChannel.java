package io.github.jtplatform.delivery.channel;

import io.github.jtplatform.delivery.metrics.ChannelDeliveryMetrics;
import io.github.jtplatform.delivery.metrics.DeliveryMetrics;
import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.delivery.publisher.PublishDisposition;
import io.github.jtplatform.delivery.publisher.PublishResult;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AsyncMessageChannel implements MessagePublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncMessageChannel.class);

    private final String channelName;
    private final AsyncChannelOptions options;
    private final ChannelDeliveryMetrics metrics;
    private final ExponentialBackoff backoff;
    private final CircuitBreaker circuitBreaker;
    private final List<Lane> lanes;
    private final FileMessageSpool spool;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private final ScheduledThreadPoolExecutor scheduler;
    private final ExecutorService transportExecutor;
    private final int bestEffortLimit;
    private long nextSequence;
    private int queued;
    private int spooled;
    private int bestEffortQueued;
    private boolean accepting = true;
    private boolean running = true;
    private boolean recoveredDeliveriesStarted;

    protected AsyncMessageChannel(String channelName, AsyncChannelOptions options) {
        this.channelName = requiredName(channelName);
        this.options = Objects.requireNonNull(options, "options");
        this.metrics = new ChannelDeliveryMetrics(channelName);
        this.backoff = new ExponentialBackoff(options.initialRetryDelay(), options.maximumRetryDelay());
        this.circuitBreaker = new CircuitBreaker(options.circuitFailureThreshold(), options.circuitOpenDuration());
        this.bestEffortLimit = options.queueCapacity() - options.criticalReserve();
        this.lanes = new ArrayList<>(options.stripes());
        for (int index = 0; index < options.stripes(); index++) {
            lanes.add(new Lane());
        }
        this.spool = new FileMessageSpool(options.overflowDirectory(), channelName, options.stripes());
        FileMessageSpool.Snapshot retained = spool.snapshot();
        this.nextSequence = retained.maximumSequence();
        for (int stripe = 0; stripe < retained.counts().length; stripe++) {
            lanes.get(stripe).spooled = retained.counts()[stripe];
            spooled += retained.counts()[stripe];
            for (int index = 0; index < retained.counts()[stripe]; index++) {
                metrics.incrementBacklog();
            }
        }
        this.scheduler = new ScheduledThreadPoolExecutor(options.stripes(), namedFactory(channelName + "-delivery-"));
        scheduler.setRemoveOnCancelPolicy(true);
        this.transportExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(channelName + "-transport-", 0).factory());
    }

    public final String channelName() {
        return channelName;
    }

    public final DeliveryMetrics metrics() {
        return metrics;
    }

    @Override
    public final PublishResult publish(MessageEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        int stripe = stripeFor(envelope.deviceId());
        Pending evicted = null;
        boolean startProcessor = false;
        PublishDisposition disposition;
        String dropReason = null;

        lock.lock();
        try {
            if (!accepting) {
                if (envelope.type().isCritical()) {
                    disposition = retainOverflow(envelope, stripe)
                            ? PublishDisposition.ACCEPTED
                            : PublishDisposition.RETRY_REQUIRED;
                } else {
                    disposition = PublishDisposition.DROPPED;
                    dropReason = "delivery channel is stopping";
                }
            } else if (envelope.type().isCritical() && lanes.get(stripe).spooled > 0) {
                disposition = retainOverflow(envelope, stripe)
                        ? PublishDisposition.ACCEPTED
                        : PublishDisposition.RETRY_REQUIRED;
            } else if (!envelope.type().isCritical() && lanes.get(stripe).spooled > 0) {
                disposition = PublishDisposition.DROPPED;
                dropReason = "an older critical message is retained for this delivery lane";
            } else if (!envelope.type().isCritical()
                    && (queued >= options.queueCapacity() || bestEffortQueued >= bestEffortLimit)) {
                disposition = PublishDisposition.DROPPED;
                dropReason = "best-effort queue capacity reached";
            } else {
                disposition = PublishDisposition.ACCEPTED;
                if (queued >= options.queueCapacity()) {
                    evicted = newestEvictableBestEffort();
                    if (evicted == null) {
                        disposition = retainOverflow(envelope, stripe)
                                ? PublishDisposition.ACCEPTED
                                : PublishDisposition.RETRY_REQUIRED;
                    } else {
                        removePending(evicted);
                    }
                }
                if (disposition == PublishDisposition.ACCEPTED && lanes.get(stripe).spooled == 0) {
                    Pending pending = new Pending(++nextSequence, envelope, stripe);
                    Lane lane = lanes.get(stripe);
                    lane.pending.addLast(pending);
                    queued++;
                    if (!envelope.type().isCritical()) {
                        bestEffortQueued++;
                    }
                    metrics.incrementBacklog();
                    if (!lane.active) {
                        lane.active = true;
                        startProcessor = true;
                    }
                }
            }
        } finally {
            lock.unlock();
        }

        if (evicted != null) {
            recordDropped(evicted.envelope, "evicted to reserve capacity for a critical message");
            deliveryTerminated(evicted.envelope);
        }
        if (disposition == PublishDisposition.DROPPED) {
            recordDropped(envelope, dropReason);
        } else if (disposition == PublishDisposition.RETRY_REQUIRED) {
            logBackpressure(envelope);
        }
        if (startProcessor) {
            scheduleStripe(stripe, Duration.ZERO);
        }
        return PublishResult.of(channelName, disposition);
    }

    protected final void startRecoveredDeliveries() {
        List<Integer> stripes = new ArrayList<>();
        lock.lock();
        try {
            if (recoveredDeliveriesStarted) {
                return;
            }
            recoveredDeliveriesStarted = true;
            for (int stripe = 0; stripe < lanes.size(); stripe++) {
                Lane lane = lanes.get(stripe);
                if (lane.spooled > 0 && !lane.active) {
                    lane.active = true;
                    stripes.add(stripe);
                }
            }
        } finally {
            lock.unlock();
        }
        stripes.forEach(stripe -> scheduleStripe(stripe, Duration.ZERO));
    }

    public final boolean awaitIdle(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long remaining = timeout.toNanos();
        lock.lockInterruptibly();
        try {
            while ((queued > 0 || spooled > 0) && remaining > 0) {
                remaining = idle.awaitNanos(remaining);
            }
            return queued == 0 && spooled == 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public final void close() {
        lock.lock();
        try {
            if (!running) {
                return;
            }
            accepting = false;
        } finally {
            lock.unlock();
        }

        boolean drained = false;
        try {
            drained = awaitIdle(options.shutdownTimeout());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        lock.lock();
        try {
            running = false;
            retainUndeliveredCriticalMessages();
            lanes.forEach(lane -> lane.active = false);
            idle.signalAll();
        } finally {
            lock.unlock();
        }
        scheduler.shutdownNow();
        transportExecutor.shutdownNow();
        channelClosed();
        if (!drained) {
            LOGGER.warn("Delivery channel {} stopped with {} accepted messages retained for retry", channelName,
                    metrics.snapshot().backlog());
        }
    }

    protected abstract CompletionStage<Void> deliver(MessageEnvelope envelope) throws Exception;

    protected void deliveryTerminated(MessageEnvelope envelope) {
    }

    protected void channelClosed() {
    }

    private int stripeFor(String deviceId) {
        return Math.floorMod(deviceId.hashCode(), lanes.size());
    }

    private boolean retainOverflow(MessageEnvelope envelope, int stripe) {
        long backpressureCount = metrics.recordBackpressured();
        long sequence = nextSequence + 1;
        try {
            spool.append(sequence, stripe, envelope);
            nextSequence = sequence;
            Lane lane = lanes.get(stripe);
            lane.spooled++;
            spooled++;
            metrics.incrementBacklog();
            if (running && accepting && !lane.active) {
                lane.active = true;
                scheduleStripe(stripe, Duration.ZERO);
            }
            if (backpressureCount == 1 || (backpressureCount & (backpressureCount - 1)) == 0) {
                LOGGER.warn("Retained critical event {} on disk for channel {} (count {})",
                        envelope.eventId(), channelName, backpressureCount);
            }
            return true;
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to retain critical event {} for channel {}; caller must retry",
                    envelope.eventId(), channelName, failure);
            return false;
        }
    }

    private void retainUndeliveredCriticalMessages() {
        for (int stripe = 0; stripe < lanes.size(); stripe++) {
            Lane lane = lanes.get(stripe);
            for (Pending pending : lane.pending) {
                if (pending.retained != null) {
                    continue;
                }
                if (pending.envelope.type().isCritical()) {
                    try {
                        spool.append(pending.sequence, stripe, pending.envelope);
                        lane.spooled++;
                        spooled++;
                    } catch (RuntimeException failure) {
                        metrics.decrementBacklog();
                        LOGGER.error("Unable to retain critical event {} while closing channel {}",
                                pending.envelope.eventId(), channelName, failure);
                    }
                } else {
                    bestEffortQueued--;
                    metrics.decrementBacklog();
                    recordDropped(pending.envelope, "delivery channel stopped before delivery");
                }
                queued--;
            }
            lane.pending.clear();
        }
    }

    private Pending newestEvictableBestEffort() {
        Pending newest = null;
        for (Lane lane : lanes) {
            Iterator<Pending> iterator = lane.pending.descendingIterator();
            while (iterator.hasNext()) {
                Pending candidate = iterator.next();
                if (!candidate.inFlight && !candidate.envelope.type().isCritical()) {
                    if (newest == null || candidate.sequence > newest.sequence) {
                        newest = candidate;
                    }
                    break;
                }
            }
        }
        return newest;
    }

    private void removePending(Pending pending) {
        if (!lanes.get(pending.stripe).pending.remove(pending)) {
            throw new IllegalStateException("pending delivery disappeared from its lane");
        }
        queued--;
        if (!pending.envelope.type().isCritical()) {
            bestEffortQueued--;
        }
        metrics.decrementBacklog();
        if (queued == 0 && spooled == 0) {
            idle.signalAll();
        }
    }

    private void scheduleStripe(int stripe, Duration delay) {
        lock.lock();
        try {
            if (!running) {
                return;
            }
        } finally {
            lock.unlock();
        }
        try {
            scheduler.schedule(() -> processStripe(stripe), delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException rejected) {
            lock.lock();
            try {
                if (running) {
                    throw rejected;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void processStripe(int stripe) {
        Pending pending;
        boolean loadRetained = false;
        lock.lock();
        try {
            Lane lane = lanes.get(stripe);
            if (!running) {
                lane.active = false;
                return;
            }
            if (lane.pending.isEmpty()) {
                if (lane.spooled == 0) {
                    lane.active = false;
                    return;
                }
                loadRetained = true;
                pending = null;
            } else {
                pending = lane.pending.peekFirst();
            }
            if (pending != null && pending.inFlight) {
                return;
            }
            if (pending != null) {
                pending.inFlight = true;
            }
        } finally {
            lock.unlock();
        }

        if (loadRetained) {
            FileMessageSpool.Entry entry;
            try {
                entry = spool.peek(stripe);
            } catch (RuntimeException failure) {
                LOGGER.error("Unable to load retained delivery for channel {} stripe {}; retrying",
                        channelName, stripe, failure);
                scheduleStripe(stripe, options.maximumRetryDelay());
                return;
            }
            if (entry == null) {
                LOGGER.error("Delivery channel {} stripe {} expects retained messages but none were found",
                        channelName, stripe);
                scheduleStripe(stripe, options.maximumRetryDelay());
                return;
            }
            pending = new Pending(entry.sequence(), entry.envelope(), stripe, entry);
            lock.lock();
            try {
                if (!running) {
                    return;
                }
                Lane lane = lanes.get(stripe);
                if (!lane.pending.isEmpty()) {
                    scheduleStripe(stripe, Duration.ZERO);
                    return;
                }
                pending.inFlight = true;
                lane.pending.addFirst(pending);
            } finally {
                lock.unlock();
            }
        }

        CircuitBreaker.Permission permission = circuitBreaker.tryAcquire();
        if (!permission.allowed()) {
            if (pending.envelope.type().isCritical()) {
                metrics.recordRetry();
                reschedule(pending, permission.retryAfter());
            } else {
                metrics.recordFailure();
                finish(pending, true, "circuit breaker is open");
            }
            return;
        }
        invokeTransport(pending, permission);
    }

    private void invokeTransport(Pending pending, CircuitBreaker.Permission permission) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ScheduledFuture<?> timeout = scheduler.schedule(
                () -> completion.completeExceptionally(new TimeoutException(
                        "delivery timed out after " + options.sendTimeout())),
                options.sendTimeout().toNanos(), TimeUnit.NANOSECONDS);
        completion.whenComplete((ignored, failure) -> {
            timeout.cancel(false);
            if (failure == null) {
                circuitBreaker.recordSuccess(permission);
                metrics.recordSuccess();
                finish(pending, false, null);
            } else {
                handleFailure(pending, permission, unwrap(failure));
            }
        });
        try {
            transportExecutor.execute(() -> {
                try {
                    CompletionStage<Void> attempt = Objects.requireNonNull(deliver(pending.envelope),
                            "delivery transport returned null");
                    attempt.whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            completion.complete(null);
                        } else {
                            completion.completeExceptionally(failure);
                        }
                    });
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            completion.completeExceptionally(rejected);
        }
    }

    private void handleFailure(Pending pending, CircuitBreaker.Permission permission, Throwable failure) {
        circuitBreaker.recordFailure(permission);
        metrics.recordFailure();
        if (pending.envelope.type().isCritical() && isRunning()) {
            pending.retryNumber++;
            metrics.recordRetry();
            Duration delay = backoff.delayForRetry(pending.retryNumber);
            LOGGER.debug("Delivery on {} failed for event {}, retry {} in {}", channelName,
                    pending.envelope.eventId(), pending.retryNumber, delay, failure);
            reschedule(pending, delay);
        } else {
            finish(pending, true, failure.getClass().getSimpleName());
        }
    }

    private boolean isRunning() {
        lock.lock();
        try {
            return running;
        } finally {
            lock.unlock();
        }
    }

    private void reschedule(Pending pending, Duration delay) {
        lock.lock();
        try {
            if (!isHead(pending) || !running) {
                return;
            }
            pending.inFlight = false;
        } finally {
            lock.unlock();
        }
        scheduleStripe(pending.stripe, delay);
    }

    private void finish(Pending pending, boolean dropped, String reason) {
        boolean scheduleNext = false;
        boolean retainedForRestart = false;
        lock.lock();
        try {
            if (!isHead(pending)) {
                return;
            }
            Lane lane = lanes.get(pending.stripe);
            lane.pending.removeFirst();
            pending.inFlight = false;
            if (pending.retained == null) {
                queued--;
                if (!pending.envelope.type().isCritical()) {
                    bestEffortQueued--;
                }
                metrics.decrementBacklog();
            } else if (dropped) {
                retainedForRestart = true;
            } else {
                try {
                    spool.complete(pending.retained);
                } catch (RuntimeException failure) {
                    LOGGER.error("Delivered event {} remains in overflow storage and may be delivered again",
                            pending.envelope.eventId(), failure);
                }
                lane.spooled--;
                spooled--;
                metrics.decrementBacklog();
            }
            if (running && (!lane.pending.isEmpty() || lane.spooled > 0)) {
                scheduleNext = true;
            } else {
                lane.active = false;
            }
            if (queued == 0 && spooled == 0) {
                idle.signalAll();
            }
        } finally {
            lock.unlock();
        }
        if (dropped && !retainedForRestart) {
            recordDropped(pending.envelope, reason == null ? "delivery failed" : reason);
        }
        if (!retainedForRestart) {
            deliveryTerminated(pending.envelope);
        }
        if (scheduleNext) {
            scheduleStripe(pending.stripe, Duration.ZERO);
        }
    }

    private boolean isHead(Pending pending) {
        return lanes.get(pending.stripe).pending.peekFirst() == pending;
    }

    private void recordDropped(MessageEnvelope envelope, String reason) {
        long typeCount = metrics.recordDropped(envelope.type());
        if (typeCount == 1 || (typeCount & (typeCount - 1)) == 0) {
            LOGGER.warn("Dropped {} message on channel {} (type count {}): {}", envelope.type(), channelName,
                    typeCount, reason);
        }
    }

    private void logBackpressure(MessageEnvelope envelope) {
        long count = metrics.snapshot().backpressured();
        if (count == 1 || (count & (count - 1)) == 0) {
            LOGGER.warn("Critical event {} was not admitted by channel {}; caller must retry (count {})",
                    envelope.eventId(), channelName, count);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> Thread.ofPlatform().daemon().name(prefix + sequence.incrementAndGet()).unstarted(runnable);
    }

    private static String requiredName(String value) {
        String normalized = Objects.requireNonNull(value, "channelName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("channelName must not be blank");
        }
        return normalized;
    }

    private static final class Lane {
        private final ArrayDeque<Pending> pending = new ArrayDeque<>();
        private int spooled;
        private boolean active;
    }

    private static final class Pending {
        private final long sequence;
        private final MessageEnvelope envelope;
        private final int stripe;
        private final FileMessageSpool.Entry retained;
        private boolean inFlight;
        private int retryNumber;

        private Pending(long sequence, MessageEnvelope envelope, int stripe) {
            this(sequence, envelope, stripe, null);
        }

        private Pending(long sequence, MessageEnvelope envelope, int stripe, FileMessageSpool.Entry retained) {
            this.sequence = sequence;
            this.envelope = envelope;
            this.stripe = stripe;
            this.retained = retained;
        }
    }
}
