package io.github.jtplatform.media.pipeline;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.media.frame.FrameAssembler;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.ingest.FragmentReassembler;
import io.github.jtplatform.media.protocol.RtpPacket;
import io.github.jtplatform.media.sink.SinkRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MediaPipeline {
    private static final Object UNTRACKED_INGRESS = new Object();

    private final FragmentReassembler reassembler;
    private final FrameAssembler frameAssembler;
    private final SinkRegistry sinks;
    private final FirstFrameListener firstFrameListener;
    private final Set<StreamKey> streamsWithFirstFrame = ConcurrentHashMap.newKeySet();
    private final Map<Object, Set<StreamKey>> streamsByIngress = new ConcurrentHashMap<>();
    private final Map<StreamKey, Set<Object>> ingressesByStream = new ConcurrentHashMap<>();
    private final Object ingressLock = new Object();

    public MediaPipeline(
            FragmentReassembler reassembler,
            FrameAssembler frameAssembler,
            SinkRegistry sinks,
            FirstFrameListener firstFrameListener) {
        this.reassembler = Objects.requireNonNull(reassembler, "reassembler");
        this.frameAssembler = Objects.requireNonNull(frameAssembler, "frameAssembler");
        this.sinks = Objects.requireNonNull(sinks, "sinks");
        this.firstFrameListener = Objects.requireNonNull(firstFrameListener, "firstFrameListener");
    }

    public List<MediaFrame> accept(RtpPacket packet) {
        return accept(UNTRACKED_INGRESS, packet);
    }

    public List<MediaFrame> accept(Object ingressSession, RtpPacket packet) {
        Objects.requireNonNull(ingressSession, "ingressSession");
        return reassembler.accept(Objects.requireNonNull(packet, "packet"))
                .map(reassembled -> {
                    List<MediaFrame> frames = frameAssembler.assemble(reassembled);
                    if (!frames.isEmpty()) {
                        try {
                            admit(ingressSession, reassembled.streamKey());
                        } catch (StreamOwnershipRejectedException rejected) {
                            frameAssembler.clear(reassembled.streamKey());
                            throw rejected;
                        }
                    }
                    frames.forEach(sinks::dispatch);
                    return frames;
                })
                .orElseGet(List::of);
    }

    public void close(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        synchronized (ingressLock) {
            Set<Object> ingresses = ingressesByStream.remove(streamKey);
            if (ingresses != null) {
                for (Object ingress : ingresses) {
                    streamsByIngress.computeIfPresent(ingress, (ignored, streamKeys) -> {
                        streamKeys.remove(streamKey);
                        return streamKeys.isEmpty() ? null : streamKeys;
                    });
                }
            }
            deactivate(streamKey);
        }
    }

    public void closeIngress(Object ingressSession) {
        Objects.requireNonNull(ingressSession, "ingressSession");
        synchronized (ingressLock) {
            Set<StreamKey> streamKeys = streamsByIngress.remove(ingressSession);
            if (streamKeys == null) {
                return;
            }
            for (StreamKey streamKey : streamKeys) {
                Set<Object> ingresses = ingressesByStream.get(streamKey);
                if (ingresses == null) {
                    continue;
                }
                ingresses.remove(ingressSession);
                if (ingresses.isEmpty() && ingressesByStream.remove(streamKey, ingresses)) {
                    deactivate(streamKey);
                }
            }
        }
    }

    /**
     * Number of distinct streams that produced a valid complete frame and still have an open ingress session.
     */
    public int activeStreamCount() {
        return streamsWithFirstFrame.size();
    }

    public Set<StreamKey> activeStreams() {
        return Set.copyOf(streamsWithFirstFrame);
    }

    public boolean isActive(StreamKey streamKey) {
        return streamsWithFirstFrame.contains(Objects.requireNonNull(streamKey, "streamKey"));
    }

    public boolean runIfActive(StreamKey streamKey, Runnable action) {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(action, "action");
        synchronized (ingressLock) {
            if (!streamsWithFirstFrame.contains(streamKey)) {
                return false;
            }
            action.run();
            return true;
        }
    }

    private void admit(Object ingressSession, StreamKey streamKey) {
        synchronized (ingressLock) {
            Set<StreamKey> ingressStreams = streamsByIngress.get(ingressSession);
            if (ingressStreams != null && ingressStreams.contains(streamKey)) {
                return;
            }
            if (!firstFrameListener.onFirstFrame(streamKey)) {
                throw new StreamOwnershipRejectedException(streamKey);
            }
            streamsByIngress.computeIfAbsent(ingressSession, ignored -> ConcurrentHashMap.newKeySet())
                    .add(streamKey);
            ingressesByStream.computeIfAbsent(streamKey, ignored -> ConcurrentHashMap.newKeySet())
                    .add(ingressSession);
            streamsWithFirstFrame.add(streamKey);
        }
    }

    private void deactivate(StreamKey streamKey) {
        boolean wasActive = streamsWithFirstFrame.remove(streamKey);
        frameAssembler.clear(streamKey);
        if (wasActive) {
            sinks.streamClosed(streamKey);
        }
    }
}
