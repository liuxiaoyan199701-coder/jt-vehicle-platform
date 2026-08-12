package io.github.jtplatform.media.talkback;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.github.jtplatform.media.protocol.Jt1078Header;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TalkbackService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TalkbackService.class);
    private static final byte G711A_SILENCE = (byte) 0xd5;

    private final TalkbackProperties properties;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final Object stateLock = new Object();
    private final Map<StreamKey, TalkbackStream> streams = new HashMap<>();
    private final Map<Channel, StreamKey> subscriptions = new HashMap<>();
    private final Set<Channel> participantCloseWatches = new HashSet<>();
    private final Set<DeviceWatch> deviceCloseWatches = new HashSet<>();
    private final LongAdder droppedFrames = new LongAdder();
    private final int mixFrameBytes;
    private final int maxQueuedBytesPerParticipant;
    private final ScheduledFuture<?> mixTask;

    public TalkbackService(TalkbackProperties properties, Clock clock) {
        this(properties, clock, newScheduler(), true);
    }

    TalkbackService(
            TalkbackProperties properties,
            Clock clock,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.properties.validate();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
        this.mixFrameBytes = properties.mixFrameBytes();
        this.maxQueuedBytesPerParticipant = Math.toIntExact(Math.multiplyExact(
                (long) mixFrameBytes, properties.getMaxQueuedFramesPerParticipant()));
        if (properties.getMode() == TalkbackMode.MIX) {
            long intervalMillis = properties.getMixInterval().toMillis();
            this.mixTask = scheduler.scheduleAtFixedRate(
                    this::flushPendingSafely,
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS);
        } else {
            this.mixTask = null;
        }
        LOGGER.info("Talkback mode: {}, mixInterval={}", properties.getMode(), properties.getMixInterval());
    }

    public boolean subscribe(StreamKey streamKey, Channel participant) {
        requireTalkback(streamKey);
        Objects.requireNonNull(participant, "participant");
        if (!participant.isActive()) {
            return false;
        }

        boolean addCloseWatch;
        synchronized (stateLock) {
            StreamKey previous = subscriptions.get(participant);
            TalkbackStream target = streams.computeIfAbsent(streamKey, ignored -> new TalkbackStream());
            if (properties.getMode() == TalkbackMode.EXCLUSIVE
                    && !target.participants.isEmpty()
                    && !target.participants.containsKey(participant)) {
                return false;
            }

            if (previous != null && !previous.equals(streamKey)) {
                removeParticipantLocked(previous, participant);
            }
            target.participants.computeIfAbsent(participant, ignored -> new AudioQueue());
            subscriptions.put(participant, streamKey);
            addCloseWatch = participantCloseWatches.add(participant);
        }

        if (addCloseWatch) {
            participant.closeFuture().addListener(ignored -> participantClosed(participant));
        }
        return true;
    }

    public void unsubscribe(Channel participant) {
        if (participant == null) {
            return;
        }
        synchronized (stateLock) {
            unsubscribeLocked(participant);
        }
    }

    public void registerDeviceChannel(StreamKey streamKey, Channel deviceChannel, Jt1078Header header) {
        requireTalkback(streamKey);
        Objects.requireNonNull(deviceChannel, "deviceChannel");
        Objects.requireNonNull(header, "header");

        DeviceWatch watch = new DeviceWatch(streamKey, deviceChannel);
        boolean addCloseWatch;
        synchronized (stateLock) {
            TalkbackStream stream = streams.computeIfAbsent(streamKey, ignored -> new TalkbackStream());
            if (stream.deviceChannel != deviceChannel) {
                stream.deviceChannel = deviceChannel;
                stream.deviceGeneration++;
            }
            if (header.payloadType() == Jt1078Constants.PT_G711A) {
                stream.payloadType = header.payloadType();
            }
            addCloseWatch = deviceCloseWatches.add(watch);
        }
        if (addCloseWatch) {
            deviceChannel.closeFuture().addListener(ignored -> deviceClosed(watch));
        }
    }

    public TalkbackUploadResult upload(StreamKey streamKey, Channel participant, byte[] audio) {
        requireTalkback(streamKey);
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(audio, "audio");
        if (audio.length == 0 || audio.length > properties.getMaxFrameBytes()) {
            return TalkbackUploadResult.FRAME_TOO_LARGE;
        }

        PendingWrite pendingWrite = null;
        synchronized (stateLock) {
            if (!streamKey.equals(subscriptions.get(participant))) {
                return TalkbackUploadResult.NOT_SUBSCRIBED;
            }
            TalkbackStream stream = streams.get(streamKey);
            if (stream == null || !stream.participants.containsKey(participant)) {
                return TalkbackUploadResult.NOT_SUBSCRIBED;
            }
            if (stream.deviceChannel == null || !stream.deviceChannel.isActive()) {
                return TalkbackUploadResult.DEVICE_NOT_CONNECTED;
            }

            if (properties.getMode() == TalkbackMode.EXCLUSIVE) {
                pendingWrite = reserveWriteLocked(streamKey, stream, audio.clone());
            } else {
                AudioQueue queue = stream.participants.get(participant);
                if (queue.append(audio, maxQueuedBytesPerParticipant)) {
                    droppedFrames.increment();
                }
            }
        }

        if (pendingWrite != null) {
            dispatch(pendingWrite);
        }
        return TalkbackUploadResult.ACCEPTED;
    }

    void flushPending() {
        List<PendingWrite> pendingWrites = new ArrayList<>();
        synchronized (stateLock) {
            for (Map.Entry<StreamKey, TalkbackStream> entry : streams.entrySet()) {
                List<byte[]> frames = new ArrayList<>();
                TalkbackStream stream = entry.getValue();
                for (AudioQueue queue : stream.participants.values()) {
                    byte[] frame = queue.drain(mixFrameBytes);
                    if (frame != null) {
                        frames.add(frame);
                    }
                }
                if (frames.isEmpty()) {
                    continue;
                }
                byte[] audio = frames.size() == 1
                        ? frames.getFirst()
                        : G711ALaw.mix(frames.toArray(byte[][]::new));
                PendingWrite pending = reserveWriteLocked(entry.getKey(), stream, audio);
                if (pending != null) {
                    pendingWrites.add(pending);
                }
            }
        }
        pendingWrites.forEach(this::dispatch);
    }

    public int participantCount(StreamKey streamKey) {
        synchronized (stateLock) {
            TalkbackStream stream = streams.get(streamKey);
            return stream == null ? 0 : stream.participants.size();
        }
    }

    public long droppedFrameCount() {
        return droppedFrames.sum();
    }

    public TalkbackMode mode() {
        return properties.getMode();
    }

    private PendingWrite reserveWriteLocked(StreamKey streamKey, TalkbackStream stream, byte[] audio) {
        Channel device = stream.deviceChannel;
        if (device == null || !device.isActive()) {
            return null;
        }
        if (!device.isWritable()
                || stream.pendingDeviceWrites >= properties.getMaxPendingDeviceWrites()) {
            droppedFrames.increment();
            return null;
        }
        stream.pendingDeviceWrites++;
        return new PendingWrite(
                streamKey,
                stream,
                device,
                stream.deviceGeneration,
                stream.payloadType,
                audio);
    }

    private void dispatch(PendingWrite pending) {
        Runnable write = () -> {
            if (!isCurrentWritableBinding(pending)) {
                droppedFrames.increment();
                completeWrite(pending.stream());
                return;
            }
            int sequence = pending.stream().sequence.getAndUpdate(current -> (current + 1) & 0xffff);
            ByteBuf encoded = null;
            try {
                encoded = TalkbackPacketEncoder.encode(
                        pending.streamKey(),
                        pending.audio(),
                        pending.payloadType(),
                        sequence,
                        clock);
                var writeFuture = pending.device().writeAndFlush(encoded);
                encoded = null;
                writeFuture.addListener(future -> {
                            completeWrite(pending.stream());
                            if (!future.isSuccess()) {
                                LOGGER.warn("Unable to write talkback audio for {}",
                                        pending.streamKey().externalId(), future.cause());
                            }
                        });
            } catch (RuntimeException error) {
                ReferenceCountUtil.safeRelease(encoded);
                completeWrite(pending.stream());
                LOGGER.warn("Unable to encode or write talkback audio for {}",
                        pending.streamKey().externalId(), error);
            }
        };

        try {
            if (pending.device().eventLoop().inEventLoop()) {
                write.run();
            } else {
                pending.device().eventLoop().execute(write);
            }
        } catch (RuntimeException rejected) {
            completeWrite(pending.stream());
            droppedFrames.increment();
            LOGGER.warn("Unable to enqueue talkback audio for {}",
                    pending.streamKey().externalId(), rejected);
        }
    }

    private boolean isCurrentWritableBinding(PendingWrite pending) {
        synchronized (stateLock) {
            return streams.get(pending.streamKey()) == pending.stream()
                    && pending.stream().deviceChannel == pending.device()
                    && pending.stream().deviceGeneration == pending.deviceGeneration()
                    && pending.device().isActive()
                    && pending.device().isWritable();
        }
    }

    private void completeWrite(TalkbackStream stream) {
        synchronized (stateLock) {
            if (stream.pendingDeviceWrites > 0) {
                stream.pendingDeviceWrites--;
            }
        }
    }

    private void participantClosed(Channel participant) {
        synchronized (stateLock) {
            participantCloseWatches.remove(participant);
            unsubscribeLocked(participant);
        }
    }

    private void deviceClosed(DeviceWatch watch) {
        synchronized (stateLock) {
            deviceCloseWatches.remove(watch);
            TalkbackStream stream = streams.get(watch.streamKey());
            if (stream == null || stream.deviceChannel != watch.channel()) {
                return;
            }
            stream.deviceChannel = null;
            stream.deviceGeneration++;
            pruneLocked(watch.streamKey(), stream);
        }
    }

    private void unsubscribeLocked(Channel participant) {
        StreamKey streamKey = subscriptions.remove(participant);
        if (streamKey != null) {
            removeParticipantLocked(streamKey, participant);
        }
    }

    private void removeParticipantLocked(StreamKey streamKey, Channel participant) {
        TalkbackStream stream = streams.get(streamKey);
        if (stream == null) {
            return;
        }
        stream.participants.remove(participant);
        pruneLocked(streamKey, stream);
    }

    private void pruneLocked(StreamKey streamKey, TalkbackStream stream) {
        if (stream.participants.isEmpty() && stream.deviceChannel == null) {
            streams.remove(streamKey, stream);
        }
    }

    private void flushPendingSafely() {
        try {
            flushPending();
        } catch (RuntimeException error) {
            LOGGER.error("Talkback mixer tick failed", error);
        }
    }

    @Override
    public void close() {
        if (mixTask != null) {
            mixTask.cancel(false);
        }
        if (ownsScheduler) {
            scheduler.shutdown();
        }
        synchronized (stateLock) {
            streams.clear();
            subscriptions.clear();
            participantCloseWatches.clear();
            deviceCloseWatches.clear();
        }
    }

    private static void requireTalkback(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        if (streamKey.streamKind() != StreamKind.TALKBACK) {
            throw new IllegalArgumentException("Talkback service requires streamKind=talkback");
        }
    }

    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "talkback-mixer");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    private static final class AudioQueue {
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int headOffset;
        private int queuedBytes;

        private boolean append(byte[] audio, int maxBytes) {
            chunks.addLast(audio.clone());
            queuedBytes += audio.length;
            int overflow = queuedBytes - maxBytes;
            if (overflow <= 0) {
                return false;
            }
            discard(overflow);
            return true;
        }

        private byte[] drain(int frameBytes) {
            if (queuedBytes == 0) {
                return null;
            }
            byte[] frame = new byte[frameBytes];
            Arrays.fill(frame, G711A_SILENCE);
            int written = 0;
            while (written < frameBytes && !chunks.isEmpty()) {
                byte[] head = chunks.getFirst();
                int count = Math.min(frameBytes - written, head.length - headOffset);
                System.arraycopy(head, headOffset, frame, written, count);
                written += count;
                headOffset += count;
                queuedBytes -= count;
                if (headOffset == head.length) {
                    chunks.removeFirst();
                    headOffset = 0;
                }
            }
            return frame;
        }

        private void discard(int bytes) {
            int remaining = bytes;
            while (remaining > 0 && !chunks.isEmpty()) {
                byte[] head = chunks.getFirst();
                int count = Math.min(remaining, head.length - headOffset);
                remaining -= count;
                queuedBytes -= count;
                headOffset += count;
                if (headOffset == head.length) {
                    chunks.removeFirst();
                    headOffset = 0;
                }
            }
        }
    }

    private static final class TalkbackStream {
        private final Map<Channel, AudioQueue> participants = new LinkedHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger();
        private Channel deviceChannel;
        private long deviceGeneration;
        private int pendingDeviceWrites;
        private int payloadType = Jt1078Constants.PT_G711A;
    }

    private record DeviceWatch(StreamKey streamKey, Channel channel) {}

    private record PendingWrite(
            StreamKey streamKey,
            TalkbackStream stream,
            Channel device,
            long deviceGeneration,
            int payloadType,
            byte[] audio) {}
}
