package io.github.jtplatform.media.netty;

import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.port.StreamRegistry;
import io.github.jtplatform.common.port.StreamSubscriptionPort;
import io.github.jtplatform.media.config.LegacyMediaPortValidator;
import io.github.jtplatform.media.config.MediaRuntimeProperties;
import io.github.jtplatform.media.config.MediaServerProperties;
import io.github.jtplatform.media.metrics.MediaNodeLoadMonitor;
import io.github.jtplatform.media.pipeline.MediaPipeline;
import io.github.jtplatform.media.protocol.Jt1078RtpDecoder;
import io.github.jtplatform.media.recording.RecordSink;
import io.github.jtplatform.media.recording.RecordingPlaybackService;
import io.github.jtplatform.media.recording.RecordingStorageMetrics;
import io.github.jtplatform.media.sink.WebSocketRawSink;
import io.github.jtplatform.media.talkback.TalkbackService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class MediaNodeServer implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaNodeServer.class);

    private final MediaServerProperties properties;
    private final LegacyMediaPortValidator portValidator;
    private final MediaPipeline pipeline;
    private final WebSocketRawSink webSocketRawSink;
    private final int instanceNumber;
    private final boolean websocketAuthenticationEnabled;
    private final StreamTokenStore streamTokens;
    private final String mediaInstanceId;
    private final StreamRegistry streamRegistry;
    private final MediaNodeLoadMonitor loadMonitor;
    private final MediaRuntimeProperties.Capacity capacity;
    private final RecordingStorageMetrics recordingStorageMetrics;
    private final RecordSink recordSink;
    private final TalkbackService talkbackService;
    private final RecordingPlaybackService recordingPlaybackService;
    private final StreamSubscriptionPort streamSubscriptions;
    private final List<Channel> serverChannels = new ArrayList<>();

    private volatile boolean running;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private MediaPorts ports;

    public MediaNodeServer(
            MediaServerProperties properties,
            LegacyMediaPortValidator portValidator,
            MediaPipeline pipeline,
            WebSocketRawSink webSocketRawSink,
            int instanceNumber,
            boolean websocketAuthenticationEnabled,
            StreamTokenStore streamTokens,
            String mediaInstanceId,
            StreamRegistry streamRegistry,
            MediaNodeLoadMonitor loadMonitor,
            MediaRuntimeProperties.Capacity capacity,
            RecordingStorageMetrics recordingStorageMetrics,
            RecordSink recordSink,
            TalkbackService talkbackService,
            RecordingPlaybackService recordingPlaybackService,
            StreamSubscriptionPort streamSubscriptions) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.portValidator = Objects.requireNonNull(portValidator, "portValidator");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.webSocketRawSink = Objects.requireNonNull(webSocketRawSink, "webSocketRawSink");
        this.instanceNumber = instanceNumber;
        this.websocketAuthenticationEnabled = websocketAuthenticationEnabled;
        this.streamTokens = websocketAuthenticationEnabled
                ? Objects.requireNonNull(streamTokens, "streamTokens")
                : streamTokens;
        this.mediaInstanceId = requireText(mediaInstanceId, "mediaInstanceId");
        this.streamRegistry = streamRegistry;
        this.loadMonitor = Objects.requireNonNull(loadMonitor, "loadMonitor");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.recordingStorageMetrics = recordingStorageMetrics;
        this.recordSink = Objects.requireNonNull(recordSink, "recordSink");
        this.talkbackService = Objects.requireNonNull(talkbackService, "talkbackService");
        this.recordingPlaybackService = Objects.requireNonNull(
                recordingPlaybackService, "recordingPlaybackService");
        this.streamSubscriptions = streamSubscriptions;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        properties.validate();
        ports = portValidator.validateAndResolve(instanceNumber);
        bossGroup = new NioEventLoopGroup(properties.getBossThreads());
        workerGroup = new NioEventLoopGroup(properties.getWorkerThreads());
        try {
            serverChannels.add(bindManagement(ports.management()));
            serverChannels.add(bindIngest(ports.main(), StreamKind.MAIN));
            serverChannels.add(bindIngest(ports.sub(), StreamKind.SUB));
            serverChannels.add(bindIngest(ports.playback(), StreamKind.PLAYBACK));
            serverChannels.add(bindIngest(ports.talkback(), StreamKind.TALKBACK));
            serverChannels.add(bindWebSocket(ports.websocket()));
            running = true;
            LOGGER.info("JT/T 1078 media instance {} started: management={}, main={}, sub={}, playback={}, "
                            + "talkback={}, websocket={}, websocketAuthentication={}",
                    instanceNumber, ports.management(), ports.main(), ports.sub(), ports.playback(),
                    ports.talkback(), ports.websocket(),
                    websocketAuthenticationEnabled ? "ENABLED" : "DISABLED");
        } catch (RuntimeException failure) {
            stopInternal();
            throw new IllegalStateException("Failed to start JT/T 1078 media instance " + instanceNumber
                    + " on derived port group 78N0-78N6", failure);
        }
    }

    private Channel bindIngest(int port, StreamKind streamKind) {
        return bind(baseBootstrap().childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                channel.pipeline().addLast("rtpDecoder",
                        new Jt1078RtpDecoder(streamKind, properties.getMaxPayloadBytes()));
                channel.pipeline().addLast("mediaPipeline", new MediaIngressHandler(
                        pipeline,
                        mediaInstanceId,
                        streamKind == StreamKind.TALKBACK ? talkbackService : null));
            }
        }), port);
    }

    private Channel bindWebSocket(int port) {
        return bind(baseBootstrap()
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(64 * 1024, 256 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast("http", new HttpServerCodec());
                        channel.pipeline().addLast("httpAggregate", new HttpObjectAggregator(64 * 1024));
                        channel.pipeline().addLast("authentication", new MediaWebSocketAuthenticationHandler(
                                websocketAuthenticationEnabled, streamTokens, mediaInstanceId));
                        WebSocketServerProtocolConfig websocketConfig = WebSocketServerProtocolConfig.newBuilder()
                                .websocketPath(properties.getWebsocketPath())
                                .checkStartsWith(true)
                                .allowExtensions(true)
                                .maxFramePayloadLength(properties.getWebsocketMaxFrameBytes())
                                .build();
                        channel.pipeline().addLast("websocket",
                                new WebSocketServerProtocolHandler(websocketConfig));
                        channel.pipeline().addLast("subscription",
                                new MediaWebSocketHandler(
                                        webSocketRawSink,
                                        streamRegistry,
                                        talkbackService,
                                        recordingPlaybackService,
                                        streamSubscriptions));
                    }
                }), port);
    }

    private Channel bindManagement(int port) {
        return bind(baseBootstrap().childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                channel.pipeline().addLast("http", new HttpServerCodec());
                channel.pipeline().addLast("httpAggregate", new HttpObjectAggregator(16 * 1024));
                channel.pipeline().addLast("health", new MediaManagementHandler(
                        ports, loadMonitor, capacity, recordingStorageMetrics, recordSink, pipeline));
            }
        }), port);
    }

    private ServerBootstrap baseBootstrap() {
        return new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, properties.getBacklog())
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_RCVBUF, 2 * 1024 * 1024)
                .childOption(ChannelOption.SO_SNDBUF, 2 * 1024 * 1024);
    }

    private Channel bind(ServerBootstrap bootstrap, int port) {
        ChannelFuture future = bootstrap.bind(properties.getBindAddress(), port).awaitUninterruptibly();
        if (!future.isSuccess()) {
            throw new IllegalStateException("Unable to bind media port " + port, future.cause());
        }
        return future.channel();
    }

    @Override
    public synchronized void stop() {
        stopInternal();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    private void stopInternal() {
        for (Channel channel : serverChannels) {
            channel.close().syncUninterruptibly();
        }
        serverChannels.clear();
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
        running = false;
    }

    public MediaPorts ports() {
        return ports == null ? MediaPorts.forInstance(instanceNumber) : ports;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
