package io.github.jtplatform.media.recording;

import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.common.model.StreamKey;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class RecordingPlaybackSession implements AutoCloseable {
    private final RecordingSearchService searchService;
    private final RecordingPlaybackRequest request;
    private final StreamKey sourceStream;
    private final RecordingPlaybackOutput output;
    private final ExecutorService executor;
    private final boolean paced;
    private final Runnable terminalCallback;
    private final ReentrantLock controlLock = new ReentrantLock();
    private final Condition controlChanged = controlLock.newCondition();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicBoolean terminalNotified = new AtomicBoolean();

    private volatile RecordingPlaybackState state = RecordingPlaybackState.STARTING;
    private volatile long positionTimestampUs;
    private long requestedTimestampUs;
    private long generation;
    private boolean paused;
    private boolean closed;
    private Future<?> task;

    RecordingPlaybackSession(
            RecordingSearchService searchService,
            RecordingPlaybackRequest request,
            StreamKey sourceStream,
            RecordingPlaybackOutput output,
            ExecutorService executor,
            boolean paced,
            Runnable terminalCallback) {
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.request = Objects.requireNonNull(request, "request");
        this.sourceStream = Objects.requireNonNull(sourceStream, "sourceStream");
        this.output = Objects.requireNonNull(output, "output");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.paced = paced;
        this.terminalCallback = Objects.requireNonNull(terminalCallback, "terminalCallback");
        this.requestedTimestampUs = request.startTimestampUs();
        this.positionTimestampUs = request.startTimestampUs();
    }

    void start() {
        controlLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("recording playback session is closed");
            }
            output.onStateChanged(RecordingPlaybackState.STARTING);
            if (closed) {
                throw new IllegalStateException("recording playback session was closed during startup");
            }
            task = executor.submit(this::run);
        } finally {
            controlLock.unlock();
        }
    }

    public RecordingPlaybackRequest request() {
        return request;
    }

    public RecordingPlaybackState state() {
        return state;
    }

    public long positionTimestampUs() {
        return positionTimestampUs;
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public void pause() {
        boolean changed = false;
        controlLock.lock();
        try {
            ensureControllable();
            if (!paused) {
                paused = true;
                state = RecordingPlaybackState.PAUSED;
                controlChanged.signalAll();
                changed = true;
            }
        } finally {
            controlLock.unlock();
        }
        if (changed) {
            output.onStateChanged(RecordingPlaybackState.PAUSED);
        }
    }

    public void resume() {
        boolean changed = false;
        controlLock.lock();
        try {
            ensureControllable();
            if (paused) {
                paused = false;
                state = RecordingPlaybackState.PLAYING;
                controlChanged.signalAll();
                changed = true;
            }
        } finally {
            controlLock.unlock();
        }
        if (changed) {
            output.onStateChanged(RecordingPlaybackState.PLAYING);
        }
    }

    public void seek(long timestampUs) {
        if (timestampUs < request.startTimestampUs() || timestampUs > request.endTimestampUs()) {
            throw new IllegalArgumentException("seek timestamp must be within the playback range");
        }
        controlLock.lock();
        try {
            ensureControllable();
            requestedTimestampUs = timestampUs;
            positionTimestampUs = timestampUs;
            generation++;
            paused = false;
            state = RecordingPlaybackState.STARTING;
            controlChanged.signalAll();
        } finally {
            controlLock.unlock();
        }
        output.onStateChanged(RecordingPlaybackState.STARTING);
    }

    @Override
    public void close() {
        Future<?> currentTask;
        boolean changed;
        controlLock.lock();
        try {
            changed = !closed;
            closed = true;
            paused = false;
            state = RecordingPlaybackState.CLOSED;
            controlChanged.signalAll();
            currentTask = task;
        } finally {
            controlLock.unlock();
        }
        if (!changed) {
            return;
        }
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        completion.completeExceptionally(new CancellationException("recording playback was closed"));
        try {
            output.onStateChanged(RecordingPlaybackState.CLOSED);
        } finally {
            notifyTerminal();
        }
    }

    private void run() {
        try {
            while (true) {
                ControlSnapshot snapshot = snapshot();
                if (snapshot == null) {
                    return;
                }
                transitionToPlaying(snapshot.generation());
                boolean restart = false;
                try (RecordingPlaybackCursor cursor = RecordingPlaybackCursor.open(
                        searchService,
                        sourceStream,
                        snapshot.timestampUs(),
                        request.endTimestampUs())) {
                    long previousTimestampUs = -1;
                    MediaFrame frame;
                    while ((frame = cursor.next()) != null) {
                        if (!emitWhenReady(snapshot.generation(), previousTimestampUs, frame)) {
                            restart = true;
                            break;
                        }
                        previousTimestampUs = Math.max(previousTimestampUs, frame.timestamp());
                    }
                }
                if (restart) {
                    continue;
                }
                if (complete(snapshot.generation())) {
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!isClosed()) {
                fail(interrupted);
            }
        } catch (IOException | RuntimeException failure) {
            fail(failure);
        }
    }

    private ControlSnapshot snapshot() {
        controlLock.lock();
        try {
            if (closed) {
                return null;
            }
            return new ControlSnapshot(generation, requestedTimestampUs);
        } finally {
            controlLock.unlock();
        }
    }

    private void transitionToPlaying(long expectedGeneration) {
        boolean changed = false;
        controlLock.lock();
        try {
            if (!closed && generation == expectedGeneration && !paused
                    && state != RecordingPlaybackState.PLAYING) {
                state = RecordingPlaybackState.PLAYING;
                changed = true;
            }
        } finally {
            controlLock.unlock();
        }
        if (changed) {
            output.onStateChanged(RecordingPlaybackState.PLAYING);
        }
    }

    private boolean emitWhenReady(
            long expectedGeneration,
            long previousTimestampUs,
            MediaFrame frame) throws InterruptedException {
        long timestampUs = frame.timestamp();
        long remainingNanos = paced && previousTimestampUs >= 0 && timestampUs > previousTimestampUs
                ? TimeUnit.MICROSECONDS.toNanos(timestampUs - previousTimestampUs)
                : 0;
        controlLock.lockInterruptibly();
        try {
            while (true) {
                if (closed || generation != expectedGeneration) {
                    return false;
                }
                while (paused && !closed && generation == expectedGeneration) {
                    controlChanged.await();
                }
                if (closed || generation != expectedGeneration) {
                    return false;
                }
                if (remainingNanos <= 0) {
                    positionTimestampUs = timestampUs;
                    output.onFrame(frame);
                    return true;
                }
                remainingNanos = controlChanged.awaitNanos(remainingNanos);
            }
        } finally {
            controlLock.unlock();
        }
    }

    private boolean complete(long expectedGeneration) {
        controlLock.lock();
        try {
            if (closed || generation != expectedGeneration) {
                return false;
            }
            state = RecordingPlaybackState.COMPLETED;
        } finally {
            controlLock.unlock();
        }
        try {
            output.onStateChanged(RecordingPlaybackState.COMPLETED);
        } finally {
            completion.complete(null);
            notifyTerminal();
        }
        return true;
    }

    private void fail(Throwable failure) {
        controlLock.lock();
        try {
            if (closed || state == RecordingPlaybackState.COMPLETED) {
                return;
            }
            state = RecordingPlaybackState.FAILED;
        } finally {
            controlLock.unlock();
        }
        completion.completeExceptionally(failure);
        try {
            output.onError(failure);
        } finally {
            try {
                output.onStateChanged(RecordingPlaybackState.FAILED);
            } finally {
                notifyTerminal();
            }
        }
    }

    private boolean isClosed() {
        controlLock.lock();
        try {
            return closed;
        } finally {
            controlLock.unlock();
        }
    }

    private void ensureControllable() {
        if (closed
                || state == RecordingPlaybackState.COMPLETED
                || state == RecordingPlaybackState.FAILED) {
            throw new IllegalStateException("recording playback session is not active: " + state);
        }
    }

    private void notifyTerminal() {
        if (terminalNotified.compareAndSet(false, true)) {
            terminalCallback.run();
        }
    }

    private record ControlSnapshot(long generation, long timestampUs) {
    }
}
