package io.github.jtplatform.media.netty;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamUnavailableException;
import io.github.jtplatform.common.port.StreamRegistry;
import io.github.jtplatform.common.port.StreamSubscriptionPort;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.RawMediaFrameEncoder;
import io.github.jtplatform.media.recording.RecordingPlaybackException;
import io.github.jtplatform.media.recording.RecordingPlaybackOutput;
import io.github.jtplatform.media.recording.RecordingPlaybackRequest;
import io.github.jtplatform.media.recording.RecordingPlaybackService;
import io.github.jtplatform.media.recording.RecordingPlaybackSession;
import io.github.jtplatform.media.recording.RecordingPlaybackState;
import io.github.jtplatform.media.sink.WebSocketRawSink;
import io.github.jtplatform.media.talkback.TalkbackService;
import io.github.jtplatform.media.talkback.TalkbackUploadResult;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

final class MediaWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaWebSocketHandler.class);

    private final WebSocketRawSink sink;
    private final StreamRegistry streamRegistry;
    private final TalkbackService talkbackService;
    private final RecordingPlaybackService playbackService;
    private final StreamSubscriptionPort subscriptionPort;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private StreamKey subscription;
    private long subscriptionGeneration;
    private RecordingPlaybackSession playbackSession;
    private boolean openedSubscription;

    MediaWebSocketHandler(WebSocketRawSink sink) {
        this(sink, null, null, null, null);
    }

    MediaWebSocketHandler(WebSocketRawSink sink, StreamRegistry streamRegistry) {
        this(sink, streamRegistry, null, null, null);
    }

    MediaWebSocketHandler(
            WebSocketRawSink sink,
            StreamRegistry streamRegistry,
            TalkbackService talkbackService) {
        this(sink, streamRegistry, talkbackService, null, null);
    }

    MediaWebSocketHandler(
            WebSocketRawSink sink,
            StreamRegistry streamRegistry,
            TalkbackService talkbackService,
            RecordingPlaybackService playbackService) {
        this(sink, streamRegistry, talkbackService, playbackService, null);
    }

    MediaWebSocketHandler(
            WebSocketRawSink sink,
            StreamRegistry streamRegistry,
            TalkbackService talkbackService,
            RecordingPlaybackService playbackService,
            StreamSubscriptionPort subscriptionPort) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.streamRegistry = streamRegistry;
        this.talkbackService = talkbackService;
        this.playbackService = playbackService;
        this.subscriptionPort = subscriptionPort;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) throws Exception {
        if (frame instanceof TextWebSocketFrame text) {
            handleText(context, text.text());
        } else if (frame instanceof PingWebSocketFrame ping) {
            context.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
        } else if (frame instanceof CloseWebSocketFrame) {
            context.close();
        } else if (frame instanceof BinaryWebSocketFrame binary) {
            handleTalkbackUpload(context, binary);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleText(ChannelHandlerContext context, String text) throws Exception {
        Map<String, Object> message = objectMapper.readValue(text, Map.class);
        if ("playback-control".equals(Objects.toString(message.get("type"), ""))) {
            handlePlaybackControl(context, message);
            return;
        }
        String action = Objects.toString(message.get("action"), "");
        switch (action) {
            case "subscribe" -> subscribe(context, message);
            case "unsubscribe" -> {
                clearSubscription(context);
                context.writeAndFlush(new TextWebSocketFrame("{\"type\":\"unsubscribed\"}"));
            }
            case "ping" -> context.writeAndFlush(new TextWebSocketFrame("{\"type\":\"pong\"}"));
            default -> context.writeAndFlush(new TextWebSocketFrame(
                    "{\"type\":\"error\",\"code\":\"INVALID_ACTION\"}"));
        }
    }

    private void subscribe(ChannelHandlerContext context, Map<String, Object> message) {
        String deviceId = Objects.toString(message.get("deviceId"), "").trim();
        try {
            int channel = requiredInteger(message.get("channel"));
            StreamKind streamKind = requiredStreamKind(message);
            PlaybackRange range = streamKind == StreamKind.PLAYBACK
                    ? playbackRange(message.get("startTime"), message.get("endTime"))
                    : null;
            subscribe(context, new StreamKey(deviceId, channel, streamKind), range, false);
        } catch (IllegalArgumentException invalidRequest) {
            context.writeAndFlush(new TextWebSocketFrame(
                    "{\"type\":\"error\",\"code\":\"INVALID_SUBSCRIPTION\"}"));
        }
    }

    private void subscribe(
            ChannelHandlerContext context,
            StreamKey requestedStream,
            PlaybackRange playbackRange,
            boolean openedByApi) {
        StreamKey authorizedStream = context.channel()
                .attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM)
                .get();
        if (authorizedStream != null && !authorizedStream.equals(requestedStream)) {
            context.writeAndFlush(new TextWebSocketFrame(
                    "{\"type\":\"error\",\"code\":\"AUTH_STREAM_MISMATCH\"}"));
            return;
        }
        StreamKey previous = subscription;
        if (requestedStream.streamKind() == StreamKind.TALKBACK
                && talkbackService != null
                && !talkbackService.subscribe(requestedStream, context.channel())) {
            releaseUnboundSubscription(requestedStream, openedByApi);
            context.writeAndFlush(new TextWebSocketFrame(
                    "{\"type\":\"error\",\"code\":\"TALKBACK_BUSY\"}"));
            return;
        }
        if (requestedStream.streamKind() == StreamKind.PLAYBACK) {
            startPlayback(context, requestedStream, playbackRange, openedByApi);
            return;
        }

        if (requestedStream.equals(previous)) {
            openedSubscription |= openedByApi;
        } else {
            clearSubscription(context, requestedStream.streamKind() == StreamKind.TALKBACK);
            subscription = requestedStream;
            openedSubscription = openedByApi;
        }
        long generation = ++subscriptionGeneration;
        sink.subscribe(requestedStream, context.channel());
        watchPendingFailure(context, requestedStream, generation);
    }

    private void startPlayback(
            ChannelHandlerContext context,
            StreamKey requestedStream,
            PlaybackRange playbackRange,
            boolean openedByApi) {
        if (playbackService == null) {
            releaseUnboundSubscription(requestedStream, openedByApi);
            context.writeAndFlush(new TextWebSocketFrame(errorMessage("PLAYBACK_UNAVAILABLE")));
            return;
        }
        if (playbackRange == null) {
            releaseUnboundSubscription(requestedStream, openedByApi);
            context.writeAndFlush(new TextWebSocketFrame(errorMessage("PLAYBACK_RANGE_REQUIRED")));
            return;
        }

        if (requestedStream.equals(subscription)) {
            closePlaybackSession();
            openedSubscription |= openedByApi;
        } else {
            clearSubscription(context, false);
            subscription = requestedStream;
            openedSubscription = openedByApi;
        }
        long generation = ++subscriptionGeneration;
        try {
            playbackSession = playbackService.play(
                    new RecordingPlaybackRequest(
                            requestedStream,
                            playbackRange.startTimestampUs(),
                            playbackRange.endTimestampUs()),
                    playbackOutput(context, requestedStream, generation));
            if (streamRegistry != null) {
                streamRegistry.markLive(requestedStream);
            }
        } catch (RecordingPlaybackException playbackMissing) {
            clearSubscription(context);
            context.writeAndFlush(new TextWebSocketFrame(errorMessage("RECORDING_NOT_FOUND")));
        } catch (Exception playbackFailure) {
            clearSubscription(context);
            LOGGER.warn("Unable to start recording playback for {}", requestedStream.externalId(), playbackFailure);
            context.writeAndFlush(new TextWebSocketFrame(errorMessage("PLAYBACK_START_FAILED")));
        }
    }

    private RecordingPlaybackOutput playbackOutput(
            ChannelHandlerContext context,
            StreamKey requestedStream,
            long generation) {
        return new RecordingPlaybackOutput() {
            @Override
            public void onFrame(MediaFrame frame) {
                if (!context.channel().isWritable()) {
                    throw new IllegalStateException("playback WebSocket is not writable");
                }
                byte[] encoded = RawMediaFrameEncoder.encode(frame);
                writeIfCurrent(context, requestedStream, generation,
                        new BinaryWebSocketFrame(Unpooled.wrappedBuffer(encoded)));
            }

            @Override
            public void onStateChanged(RecordingPlaybackState state) {
                String message = switch (state) {
                    case STARTING -> "{\"type\":\"state\",\"state\":\"waking\"}";
                    case PLAYING -> "{\"type\":\"state\",\"state\":\"live\"}";
                    case PAUSED -> playbackStateMessage("paused");
                    case COMPLETED -> playbackStateMessage("completed");
                    case CLOSED -> playbackStateMessage("closed");
                    case FAILED -> null;
                };
                if (message != null) {
                    writeIfCurrent(context, requestedStream, generation,
                            new TextWebSocketFrame(message));
                }
            }

            @Override
            public void onError(Throwable failure) {
                LOGGER.warn("Recording playback failed for {}", requestedStream.externalId(), failure);
                writeIfCurrent(context, requestedStream, generation,
                        new TextWebSocketFrame(errorMessage("PLAYBACK_FAILED")));
            }
        };
    }

    private void writeIfCurrent(
            ChannelHandlerContext context,
            StreamKey requestedStream,
            long generation,
            WebSocketFrame frame) {
        try {
            context.channel().eventLoop().execute(() -> {
                if (!context.channel().isActive()
                        || generation != subscriptionGeneration
                        || !requestedStream.equals(subscription)) {
                    frame.release();
                    return;
                }
                context.writeAndFlush(frame);
            });
        } catch (RuntimeException rejected) {
            frame.release();
            throw rejected;
        }
    }

    private void handlePlaybackControl(
            ChannelHandlerContext context,
            Map<String, Object> message) {
        RecordingPlaybackSession session = playbackSession;
        if (session == null
                || subscription == null
                || subscription.streamKind() != StreamKind.PLAYBACK) {
            context.writeAndFlush(new TextWebSocketFrame(errorMessage("PLAYBACK_NOT_ACTIVE")));
            return;
        }
        try {
            switch (Objects.toString(message.get("action"), "")) {
                case "pause" -> session.pause();
                case "resume" -> session.resume();
                case "seek" -> session.seek(timestampMicros(message.get("position"), "position"));
                default -> throw new IllegalArgumentException("unsupported playback action");
            }
        } catch (IllegalArgumentException | IllegalStateException invalidControl) {
            context.writeAndFlush(new TextWebSocketFrame(errorMessage("INVALID_PLAYBACK_CONTROL")));
        }
    }

    private void watchPendingFailure(
            ChannelHandlerContext context, StreamKey streamKey, long generation) {
        if (streamRegistry == null) {
            return;
        }
        streamRegistry.find(streamKey).ifPresent(entry -> entry.whenLive().whenComplete((ignored, failure) -> {
            if (failure == null) {
                return;
            }
            context.channel().eventLoop().execute(() -> {
                if (!context.channel().isActive()
                        || generation != subscriptionGeneration
                        || !streamKey.equals(subscription)) {
                    return;
                }
                context.writeAndFlush(new TextWebSocketFrame(errorMessage(failureCode(failure))));
            });
        }));
    }

    private void handleTalkbackUpload(ChannelHandlerContext context, BinaryWebSocketFrame frame) {
        if (subscription == null || subscription.streamKind() != StreamKind.TALKBACK) {
            LOGGER.warn("Rejected talkback audio from unsubscribed WebSocket channel {}", context.channel().id());
            context.writeAndFlush(new TextWebSocketFrame(
                    "{\"type\":\"error\",\"code\":\"TALKBACK_NOT_SUBSCRIBED\"}"));
            return;
        }
        if (talkbackService == null) {
            context.writeAndFlush(new TextWebSocketFrame(
                    "{\"type\":\"error\",\"code\":\"TALKBACK_UNAVAILABLE\"}"));
            return;
        }
        byte[] audio = ByteBufUtil.getBytes(frame.content());
        TalkbackUploadResult result = talkbackService.upload(subscription, context.channel(), audio);
        if (result != TalkbackUploadResult.ACCEPTED) {
            String code = switch (result) {
                case NOT_SUBSCRIBED -> "TALKBACK_NOT_SUBSCRIBED";
                case DEVICE_NOT_CONNECTED -> "TALKBACK_DEVICE_NOT_CONNECTED";
                case FRAME_TOO_LARGE -> "TALKBACK_INVALID_FRAME";
                case ACCEPTED -> throw new IllegalStateException("unreachable");
            };
            context.writeAndFlush(new TextWebSocketFrame(errorMessage(code)));
        }
    }

    private static StreamKind requiredStreamKind(Map<String, Object> message) {
        Object configured = message.get("streamKind");
        if (configured == null) {
            throw new IllegalArgumentException("streamKind is required");
        }
        return StreamKind.fromWireValue(configured.toString());
    }

    private static int requiredInteger(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("channel is required");
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake) {
            String authorizationFailure = context.channel()
                    .attr(MediaWebSocketAuthenticationHandler.AUTHORIZATION_FAILURE)
                    .get();
            if (authorizationFailure != null) {
                context.write(new TextWebSocketFrame(errorMessage(authorizationFailure)));
                context.writeAndFlush(new CloseWebSocketFrame(4003, authorizationFailure))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            StreamKey authorizedStream = context.channel()
                    .attr(MediaWebSocketAuthenticationHandler.AUTHORIZED_STREAM)
                    .get();
            if (authorizedStream != null) {
                PlaybackRange range;
                try {
                    range = authorizedStream.streamKind() == StreamKind.PLAYBACK
                            ? playbackRange(handshake.requestUri())
                            : null;
                } catch (IllegalArgumentException invalidRange) {
                    releaseUnboundSubscription(authorizedStream, true);
                    context.writeAndFlush(new TextWebSocketFrame(
                            errorMessage("INVALID_PLAYBACK_RANGE")));
                    return;
                }
                subscribe(context, authorizedStream, range, true);
            } else {
                context.writeAndFlush(new TextWebSocketFrame(
                        "{\"type\":\"welcome\",\"message\":\"Connected to JT/T 1078 media node\"}"));
            }
        }
        super.userEventTriggered(context, event);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        clearSubscription(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOGGER.warn("WebSocket media session failed: {}", context.channel().id(), cause);
        clearSubscription(context);
        context.close();
    }

    private void clearSubscription(ChannelHandlerContext context) {
        clearSubscription(context, false);
    }

    private void clearSubscription(ChannelHandlerContext context, boolean keepTalkback) {
        StreamKey releasedStream = subscription;
        boolean releaseOpenedStream = openedSubscription;
        subscriptionGeneration++;
        subscription = null;
        openedSubscription = false;
        sink.unsubscribe(context.channel());
        if (!keepTalkback) {
            releaseTalkback(context);
        }
        closePlaybackSession();
        if (releaseOpenedStream && releasedStream != null) {
            releaseSubscription(releasedStream);
        }
    }

    private void releaseUnboundSubscription(StreamKey streamKey, boolean openedByApi) {
        if (openedByApi) {
            releaseSubscription(streamKey);
        }
    }

    private void releaseSubscription(StreamKey streamKey) {
        if (subscriptionPort == null) {
            return;
        }
        try {
            subscriptionPort.release(streamKey);
        } catch (RuntimeException releaseFailure) {
            LOGGER.warn("Unable to release stream subscription for {}",
                    streamKey.externalId(), releaseFailure);
        }
    }

    private void closePlaybackSession() {
        RecordingPlaybackSession session = playbackSession;
        playbackSession = null;
        if (session != null) {
            session.close();
        }
    }

    private void releaseTalkback(ChannelHandlerContext context) {
        if (talkbackService != null) {
            talkbackService.unsubscribe(context.channel());
        }
    }

    private static String failureCode(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof StreamUnavailableException
                && "DEVICE_NO_RESPONSE".equals(current.getMessage())) {
            return "DEVICE_NO_RESPONSE";
        }
        return "STREAM_UNAVAILABLE";
    }

    private static String errorMessage(String code) {
        return "{\"type\":\"error\",\"code\":\"" + code + "\"}";
    }

    private static String playbackStateMessage(String state) {
        return "{\"type\":\"playback-state\",\"state\":\"" + state + "\"}";
    }

    private static PlaybackRange playbackRange(String requestUri) {
        Map<String, List<String>> parameters = new QueryStringDecoder(requestUri).parameters();
        return playbackRange(first(parameters, "startTime"), first(parameters, "endTime"));
    }

    private static PlaybackRange playbackRange(Object startTime, Object endTime) {
        return new PlaybackRange(
                timestampMicros(startTime, "startTime"),
                timestampMicros(endTime, "endTime"));
    }

    private static long timestampMicros(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value instanceof Number number) {
            long timestampUs = number.longValue();
            if (timestampUs < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return timestampUs;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException notNumeric) {
            try {
                Instant instant = Instant.parse(text);
                return Math.addExact(
                        Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                        instant.getNano() / 1_000L);
            } catch (DateTimeParseException | ArithmeticException invalidTimestamp) {
                throw new IllegalArgumentException(name + " is invalid", invalidTimestamp);
            }
        }
    }

    private static String first(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private record PlaybackRange(long startTimestampUs, long endTimestampUs) {
        private PlaybackRange {
            if (startTimestampUs < 0 || endTimestampUs < startTimestampUs) {
                throw new IllegalArgumentException("playback time range is invalid");
            }
        }
    }
}
