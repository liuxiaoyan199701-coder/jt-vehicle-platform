package io.github.jtplatform.media.recording;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import io.github.jtplatform.media.sink.MediaSink;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class RecordSink implements MediaSink, AutoCloseable {
    private final RecordingProperties properties;
    private final Path root;
    private final long segmentDurationUs;
    private final RecordingSegmentListener segmentListener;
    private final Clock clock;
    private final Map<StreamKey, RecordingSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public RecordSink(RecordingProperties properties) {
        this(properties, RecordingSegmentListener.noOp(), Clock.systemUTC());
    }

    public RecordSink(RecordingProperties properties, RecordingSegmentListener segmentListener) {
        this(properties, segmentListener, Clock.systemUTC());
    }

    public RecordSink(
            RecordingProperties properties,
            RecordingSegmentListener segmentListener,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        properties.validate();
        this.root = properties.getRoot().toAbsolutePath().normalize();
        this.segmentDurationUs = properties.segmentDurationMicros();
        this.segmentListener = Objects.requireNonNull(segmentListener, "segmentListener");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void accept(MediaFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (!properties.records(frame.streamKey().streamKind())) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("RecordSink is closed");
        }

        RecordingSession session = sessions.computeIfAbsent(
                frame.streamKey(), this::newSession);
        synchronized (session) {
            try {
                session.recorder.accept(frame, shouldRecord(session));
            } catch (IOException | RuntimeException failure) {
                session.recorder.abort();
                session.resetRecorder();
                throw new RecordingWriteException(
                        "Unable to write recording for " + frame.streamKey().externalId(), failure);
            }
        }
    }

    public boolean startManual(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        ensureOpen();
        if (!properties.isManualEnabled()) {
            throw new IllegalStateException("Manual recording trigger is disabled");
        }
        ensureRecordable(streamKey);
        RecordingSession session = sessions.computeIfAbsent(streamKey, this::newSession);
        synchronized (session) {
            boolean changed = !session.manual;
            session.manual = true;
            return changed;
        }
    }

    public boolean stopManual(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        RecordingSession session = sessions.get(streamKey);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            boolean changed = session.manual;
            session.manual = false;
            pauseIfInactive(streamKey, session);
            return changed;
        }
    }

    public int triggerAlarm(String deviceId, Collection<StreamKey> activeStreams) {
        String normalizedDeviceId = requireText(deviceId, "deviceId");
        Objects.requireNonNull(activeStreams, "activeStreams");
        ensureOpen();
        if (!properties.isAlarmEnabled()) {
            return 0;
        }
        int triggered = 0;
        for (StreamKey streamKey : activeStreams) {
            if (!streamKey.deviceId().equals(normalizedDeviceId)) {
                continue;
            }
            if (triggerAlarm(streamKey)) {
                triggered++;
            }
        }
        return triggered;
    }

    public boolean triggerAlarm(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        ensureOpen();
        if (!properties.isAlarmEnabled() || !properties.records(streamKey.streamKind())) {
            return false;
        }
        RecordingSession session = sessions.computeIfAbsent(streamKey, this::newSession);
        synchronized (session) {
            boolean changed = !session.alarm;
            session.alarm = true;
            return changed;
        }
    }

    public boolean isRecording(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        if (!properties.records(streamKey.streamKind())) {
            return false;
        }
        RecordingSession session = sessions.get(streamKey);
        if (session == null) {
            return properties.isContinuousEnabled();
        }
        synchronized (session) {
            return shouldRecord(session);
        }
    }

    public void close(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        RecordingSession session = sessions.remove(streamKey);
        if (session == null) {
            return;
        }
        synchronized (session) {
            try {
                session.recorder.finishSegment();
            } catch (IOException | RuntimeException failure) {
                session.recorder.abort();
                throw new RecordingWriteException(
                        "Unable to commit recording for " + streamKey.externalId(), failure);
            }
        }
    }

    @Override
    public void onStreamClosed(StreamKey streamKey) {
        close(streamKey);
    }

    @Override
    public void close() {
        closed = true;
        RecordingWriteException firstFailure = null;
        for (StreamKey streamKey : java.util.List.copyOf(sessions.keySet())) {
            try {
                close(streamKey);
            } catch (RecordingWriteException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private RecordingSession newSession(StreamKey streamKey) {
        return new RecordingSession(streamKey);
    }

    private boolean shouldRecord(RecordingSession session) {
        return properties.isContinuousEnabled() || session.manual || session.alarm;
    }

    private void pauseIfInactive(StreamKey streamKey, RecordingSession session) {
        if (shouldRecord(session)) {
            return;
        }
        try {
            session.recorder.finishSegment();
        } catch (IOException | RuntimeException failure) {
            session.recorder.abort();
            session.resetRecorder();
            throw new RecordingWriteException(
                    "Unable to commit recording for " + streamKey.externalId(), failure);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RecordSink is closed");
        }
    }

    private void ensureRecordable(StreamKey streamKey) {
        if (!properties.records(streamKey.streamKind())) {
            throw new IllegalStateException(
                    "Recording is disabled for stream kind " + streamKey.streamKind().wireValue());
        }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private final class RecordingSession {
        private final StreamKey streamKey;
        private StreamRecorder recorder;
        private boolean manual;
        private boolean alarm;

        private RecordingSession(StreamKey streamKey) {
            this.streamKey = streamKey;
            resetRecorder();
        }

        private void resetRecorder() {
            recorder = new StreamRecorder(
                    streamKey, root, segmentDurationUs, segmentListener, clock);
        }
    }

    private static final class StreamRecorder {
        private final StreamKey streamKey;
        private final Path root;
        private final long segmentDurationUs;
        private final RecordingSegmentListener segmentListener;
        private final RecordingTimestampNormalizer timestamps;
        private final EnumMap<MediaFrameType, MediaFrame> parameters =
                new EnumMap<>(MediaFrameType.class);

        private RecordingSegmentWriter writer;
        private MediaCodec latestAudioCodec = MediaCodec.UNKNOWN;

        private StreamRecorder(
                StreamKey streamKey,
                Path root,
                long segmentDurationUs,
                RecordingSegmentListener segmentListener,
                Clock clock) {
            this.streamKey = streamKey;
            this.root = root;
            this.segmentDurationUs = segmentDurationUs;
            this.segmentListener = segmentListener;
            this.timestamps = new RecordingTimestampNormalizer(clock);
        }

        private void accept(MediaFrame frame, boolean recording) throws IOException {
            frame = normalizeTimestamp(frame);
            if (!recording && writer != null) {
                finishSegment();
            }
            if (frame.type().parameterSet()) {
                acceptParameter(frame);
                return;
            }
            if (!recording) {
                return;
            }
            switch (frame.type()) {
                case VIDEO_KEY, VIDEO_DELTA, VIDEO_B -> acceptVideo(frame);
                case AUDIO -> acceptAudio(frame);
                case TRANSPARENT -> {
                    if (writer != null) {
                        writer.append(frame);
                    }
                }
                case SPS, PPS, VPS, AUDIO_CONFIG -> throw new IllegalStateException("unreachable");
            }
        }

        private MediaFrame normalizeTimestamp(MediaFrame frame) {
            long timestampUs = timestamps.normalize(frame.timestamp());
            if (timestampUs == frame.timestamp()) {
                return frame;
            }
            return new MediaFrame(
                    frame.streamKey(), frame.type(), frame.codec(), timestampUs, frame.payload());
        }

        private void acceptParameter(MediaFrame frame) throws IOException {
            MediaFrame previous = parameters.get(frame.type());
            if (sameFrame(previous, frame)) {
                return;
            }
            if (writer != null) {
                finishSegment();
            }
            parameters.put(frame.type(), frame);
            if (frame.type() == MediaFrameType.AUDIO_CONFIG) {
                latestAudioCodec = frame.codec();
            }
        }

        private void acceptVideo(MediaFrame frame) throws IOException {
            if (frame.codec() != MediaCodec.H264 && frame.codec() != MediaCodec.H265) {
                return;
            }
            if (writer != null && (!writer.hasVideo() || writer.videoCodec() != frame.codec())) {
                finishSegment();
            }
            if (writer == null) {
                if (frame.type() != MediaFrameType.VIDEO_KEY || !hasCompleteParameters(frame.codec())) {
                    return;
                }
                startVideoSegment(frame);
            } else if (frame.type() == MediaFrameType.VIDEO_KEY && durationReached(frame.timestamp())) {
                finishSegment();
                if (!hasCompleteParameters(frame.codec())) {
                    return;
                }
                startVideoSegment(frame);
            }
            writer.append(frame);
        }

        private void acceptAudio(MediaFrame frame) throws IOException {
            if (frame.codec() != MediaCodec.AAC && frame.codec() != MediaCodec.G711A) {
                return;
            }
            latestAudioCodec = frame.codec();
            if (writer == null) {
                if (!startAudioSegment(frame)) {
                    return;
                }
            } else if (!writer.hasVideo()
                    && (writer.audioCodec() != frame.codec() || durationReached(frame.timestamp()))) {
                finishSegment();
                if (!startAudioSegment(frame)) {
                    return;
                }
            }
            writer.append(frame);
        }

        private void startVideoSegment(MediaFrame keyFrame) throws IOException {
            writer = new RecordingSegmentWriter(
                    root, streamKey, keyFrame.timestamp(), keyFrame.codec(), latestAudioCodec);
            if (keyFrame.codec() == MediaCodec.H265) {
                appendParameter(MediaFrameType.VPS, keyFrame.timestamp());
            }
            appendParameter(MediaFrameType.SPS, keyFrame.timestamp());
            appendParameter(MediaFrameType.PPS, keyFrame.timestamp());
            if (latestAudioCodec == MediaCodec.AAC && parameters.containsKey(MediaFrameType.AUDIO_CONFIG)) {
                appendParameter(MediaFrameType.AUDIO_CONFIG, keyFrame.timestamp());
            }
        }

        private boolean startAudioSegment(MediaFrame audioFrame) throws IOException {
            if (audioFrame.codec() == MediaCodec.AAC && !parameters.containsKey(MediaFrameType.AUDIO_CONFIG)) {
                return false;
            }
            writer = new RecordingSegmentWriter(
                    root, streamKey, audioFrame.timestamp(), MediaCodec.UNKNOWN, audioFrame.codec());
            if (audioFrame.codec() == MediaCodec.AAC) {
                appendParameter(MediaFrameType.AUDIO_CONFIG, audioFrame.timestamp());
            }
            return true;
        }

        private void appendParameter(MediaFrameType type, long timestampUs) throws IOException {
            MediaFrame parameter = parameters.get(type);
            if (parameter == null) {
                throw new IllegalStateException("Missing recording parameter set " + type);
            }
            writer.append(new MediaFrame(
                    streamKey, parameter.type(), parameter.codec(), timestampUs, parameter.payload()));
        }

        private boolean hasCompleteParameters(MediaCodec codec) {
            if (codec == MediaCodec.H264) {
                return parameterMatches(MediaFrameType.SPS, codec)
                        && parameterMatches(MediaFrameType.PPS, codec);
            }
            return parameterMatches(MediaFrameType.VPS, codec)
                    && parameterMatches(MediaFrameType.SPS, codec)
                    && parameterMatches(MediaFrameType.PPS, codec);
        }

        private boolean parameterMatches(MediaFrameType type, MediaCodec codec) {
            MediaFrame frame = parameters.get(type);
            return frame != null && frame.codec() == codec;
        }

        private boolean durationReached(long timestampUs) {
            return timestampUs >= writer.startTimestampUs()
                    && timestampUs - writer.startTimestampUs() >= segmentDurationUs;
        }

        private void finishSegment() throws IOException {
            RecordingSegmentWriter completed = writer;
            writer = null;
            if (completed != null) {
                segmentListener.onCommitted(completed.commit());
            }
        }

        private void abort() {
            RecordingSegmentWriter failed = writer;
            writer = null;
            if (failed != null) {
                failed.abort();
            }
        }

        private static boolean sameFrame(MediaFrame left, MediaFrame right) {
            return left != null
                    && left.codec() == right.codec()
                    && Arrays.equals(left.payload(), right.payload());
        }
    }
}
