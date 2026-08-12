package io.github.jtplatform.media.recording;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.config.RecordingProperties;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RecordingPlaybackService implements AutoCloseable {
    private final RecordingSearchService searchService;
    private final ExecutorService executor;
    private final boolean paced;
    private final boolean ownsExecutor;
    private final Set<RecordingPlaybackSession> sessions = ConcurrentHashMap.newKeySet();

    private volatile boolean closed;

    public RecordingPlaybackService(RecordingProperties properties) {
        this(new RecordingSearchService(properties));
    }

    public RecordingPlaybackService(RecordingSearchService searchService) {
        this(
                searchService,
                Executors.newCachedThreadPool(
                        Thread.ofPlatform().daemon().name("recording-playback-", 0).factory()),
                true,
                true);
    }

    RecordingPlaybackService(
            RecordingSearchService searchService,
            ExecutorService executor,
            boolean paced,
            boolean ownsExecutor) {
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.paced = paced;
        this.ownsExecutor = ownsExecutor;
    }

    public RecordingPlaybackSession play(
            RecordingPlaybackRequest request,
            RecordingPlaybackOutput output) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(output, "output");
        ensureOpen();

        StreamKey sourceStream = resolveSourceStream(request);
        RecordingPlaybackSession[] holder = new RecordingPlaybackSession[1];
        RecordingPlaybackSession session = new RecordingPlaybackSession(
                searchService,
                request,
                sourceStream,
                output,
                executor,
                paced,
                () -> sessions.remove(holder[0]));
        holder[0] = session;
        sessions.add(session);
        if (closed) {
            session.close();
            throw new IllegalStateException("RecordingPlaybackService is closed");
        }
        try {
            session.start();
            return session;
        } catch (RuntimeException | Error startupFailure) {
            sessions.remove(session);
            try {
                session.close();
            } catch (RuntimeException | Error closeFailure) {
                startupFailure.addSuppressed(closeFailure);
            }
            throw startupFailure;
        }
    }

    @Override
    public void close() {
        closed = true;
        for (RecordingPlaybackSession session : List.copyOf(sessions)) {
            session.close();
        }
        sessions.clear();
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RecordingPlaybackService is closed");
        }
    }

    private StreamKey resolveSourceStream(RecordingPlaybackRequest request) throws IOException {
        StreamKey requested = request.streamKey();
        if (hasRecording(requested, request)) {
            return requested;
        }
        if (requested.streamKind() == StreamKind.PLAYBACK) {
            for (StreamKind sourceKind : List.of(StreamKind.MAIN, StreamKind.SUB)) {
                StreamKey candidate = new StreamKey(
                        requested.deviceId(), requested.channel(), sourceKind);
                if (hasRecording(candidate, request)) {
                    return candidate;
                }
            }
        }
        throw new RecordingPlaybackException(
                "No recording is available in the requested range for "
                        + requested.externalId());
    }

    private boolean hasRecording(
            StreamKey streamKey, RecordingPlaybackRequest request) throws IOException {
        return !searchService.search(
                streamKey, request.startTimestampUs(), request.endTimestampUs()).isEmpty();
    }

    public int activeSessionCount() {
        return sessions.size();
    }
}
