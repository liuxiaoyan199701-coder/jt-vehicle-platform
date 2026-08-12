package io.github.jtplatform.media.sink;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import io.github.jtplatform.media.frame.RawMediaFrameEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class WebSocketRawSink implements MediaSink {
    private final Map<StreamKey, SubscriberGroup> groups = new ConcurrentHashMap<>();
    private final Map<ChannelId, StreamKey> subscriptions = new ConcurrentHashMap<>();
    private final Map<ChannelId, LongAdder> droppedFrames = new ConcurrentHashMap<>();
    private final Set<ChannelId> closeWatches = ConcurrentHashMap.newKeySet();
    private final LongAdder outboundBytes = new LongAdder();

    public void subscribe(StreamKey streamKey, Channel channel) {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(channel, "channel");
        unsubscribe(channel);
        SubscriberGroup group = groups.computeIfAbsent(streamKey, ignored -> new SubscriberGroup());
        group.subscribers.add(channel);
        subscriptions.put(channel.id(), streamKey);
        if (closeWatches.add(channel.id())) {
            channel.closeFuture().addListener(ignored -> {
                closeWatches.remove(channel.id());
                unsubscribe(channel);
            });
        }

        SubscriberSnapshot snapshot = group.snapshot();
        execute(channel, () -> {
            if (!channel.isActive()) {
                return;
            }
            channel.write(new TextWebSocketFrame(stateMessage(snapshot.live() ? "live" : "waking")));
            if (snapshot.live()) {
                for (MediaFrame frame : snapshot.bootstrapFrames()) {
                    writeFrame(channel, frame);
                }
            }
            channel.flush();
        });
    }

    public void unsubscribe(Channel channel) {
        if (channel == null) {
            return;
        }
        StreamKey streamKey = subscriptions.remove(channel.id());
        droppedFrames.remove(channel.id());
        if (streamKey == null) {
            return;
        }
        groups.computeIfPresent(streamKey, (key, group) -> {
            group.subscribers.remove(channel);
            return group.subscribers.isEmpty() && !group.live ? null : group;
        });
    }

    public int subscriberCount(StreamKey streamKey) {
        SubscriberGroup group = groups.get(streamKey);
        return group == null ? 0 : group.subscribers.size();
    }

    public long droppedFrameCount(Channel channel) {
        LongAdder counter = droppedFrames.get(channel.id());
        return counter == null ? 0L : counter.sum();
    }

    public boolean isLive(StreamKey streamKey) {
        SubscriberGroup group = groups.get(streamKey);
        return group != null && group.live;
    }

    public long outboundBytes() {
        return outboundBytes.sum();
    }

    @Override
    public void accept(MediaFrame frame) {
        Objects.requireNonNull(frame, "frame");
        SubscriberGroup group = groups.computeIfAbsent(frame.streamKey(), ignored -> new SubscriberGroup());
        if (frame.codec() == MediaCodec.UNKNOWN
                && (frame.type() == MediaFrameType.VIDEO_KEY
                || frame.type() == MediaFrameType.VIDEO_DELTA
                || frame.type() == MediaFrameType.VIDEO_B)) {
            notifyUnsupportedCodec(group);
            return;
        }

        boolean becameLive = group.cache(frame);
        for (Channel subscriber : group.subscribers) {
            if (!subscriber.isActive()) {
                unsubscribe(subscriber);
                continue;
            }
            if (!subscriber.isWritable()) {
                droppedFrames.computeIfAbsent(subscriber.id(), ignored -> new LongAdder()).increment();
                continue;
            }
            execute(subscriber, () -> {
                if (!subscriber.isActive() || !subscriber.isWritable()) {
                    droppedFrames.computeIfAbsent(subscriber.id(), ignored -> new LongAdder()).increment();
                    return;
                }
                if (becameLive) {
                    subscriber.write(new TextWebSocketFrame(stateMessage("live")));
                }
                writeFrame(subscriber, frame);
                subscriber.flush();
            });
        }
    }

    private void notifyUnsupportedCodec(SubscriberGroup group) {
        if (!group.unsupportedCodecNotified.compareAndSet(false, true)) {
            return;
        }
        for (Channel subscriber : group.subscribers) {
            execute(subscriber, () -> subscriber.writeAndFlush(new TextWebSocketFrame(
                    "{\"type\":\"error\",\"code\":\"UNSUPPORTED_CODEC\"}")));
        }
    }

    private static void execute(Channel channel, Runnable task) {
        if (channel.eventLoop().inEventLoop()) {
            task.run();
        } else {
            channel.eventLoop().execute(task);
        }
    }

    private void writeFrame(Channel channel, MediaFrame frame) {
        ByteBuf encoded = Unpooled.wrappedBuffer(RawMediaFrameEncoder.encode(frame));
        int encodedBytes = encoded.readableBytes();
        channel.write(new BinaryWebSocketFrame(encoded)).addListener(future -> {
            if (future.isSuccess()) {
                outboundBytes.add(encodedBytes);
            }
        });
    }

    private static String stateMessage(String state) {
        return "{\"type\":\"state\",\"state\":\"" + state + "\"}";
    }

    private static final class SubscriberGroup {
        private final Set<Channel> subscribers = ConcurrentHashMap.newKeySet();
        private final Map<MediaFrameType, MediaFrame> parameters = new EnumMap<>(MediaFrameType.class);
        private final java.util.concurrent.atomic.AtomicBoolean unsupportedCodecNotified =
                new java.util.concurrent.atomic.AtomicBoolean();
        private MediaFrame gopHeader;
        private volatile boolean live;

        private synchronized boolean cache(MediaFrame frame) {
            if (frame.type().parameterSet()) {
                parameters.put(frame.type(), frame);
                return false;
            }
            if (frame.type() == MediaFrameType.VIDEO_KEY) {
                gopHeader = frame;
            }
            boolean changed = !live;
            live = true;
            return changed;
        }

        private synchronized SubscriberSnapshot snapshot() {
            List<MediaFrame> bootstrap = new ArrayList<>();
            addIfPresent(bootstrap, MediaFrameType.VPS);
            addIfPresent(bootstrap, MediaFrameType.SPS);
            addIfPresent(bootstrap, MediaFrameType.PPS);
            addIfPresent(bootstrap, MediaFrameType.AUDIO_CONFIG);
            if (gopHeader != null) {
                bootstrap.add(gopHeader);
            }
            return new SubscriberSnapshot(live, List.copyOf(bootstrap));
        }

        private void addIfPresent(List<MediaFrame> target, MediaFrameType type) {
            MediaFrame frame = parameters.get(type);
            if (frame != null) {
                target.add(frame);
            }
        }
    }

    private record SubscriberSnapshot(boolean live, List<MediaFrame> bootstrapFrames) {}
}
