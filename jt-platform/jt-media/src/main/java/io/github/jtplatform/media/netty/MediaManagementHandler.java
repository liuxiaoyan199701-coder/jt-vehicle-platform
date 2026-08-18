package io.github.jtplatform.media.netty;

import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.media.config.MediaRuntimeProperties;
import io.github.jtplatform.media.metrics.MediaNodeLoadMonitor;
import io.github.jtplatform.media.pipeline.MediaPipeline;
import io.github.jtplatform.media.protocol.SimWidthStats;
import io.github.jtplatform.media.recording.RecordSink;
import io.github.jtplatform.media.recording.RecordingStorageMetrics;
import io.github.jtplatform.media.recording.RecordingStorageSnapshot;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MediaManagementHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaManagementHandler.class);

    private final MediaPorts ports;
    private final MediaNodeLoadMonitor loadMonitor;
    private final MediaRuntimeProperties.Capacity capacity;
    private final RecordingStorageMetrics recordingStorageMetrics;
    private final RecordSink recordSink;
    private final MediaPipeline mediaPipeline;

    MediaManagementHandler(MediaPorts ports) {
        this(ports, new MediaNodeLoadMonitor(() -> 0, () -> 0L, Clock.systemUTC()),
                new MediaRuntimeProperties.Capacity(), null, null, null);
    }

    MediaManagementHandler(
            MediaPorts ports,
            MediaNodeLoadMonitor loadMonitor,
            MediaRuntimeProperties.Capacity capacity) {
        this(ports, loadMonitor, capacity, null, null, null);
    }

    MediaManagementHandler(
            MediaPorts ports,
            MediaNodeLoadMonitor loadMonitor,
            MediaRuntimeProperties.Capacity capacity,
            RecordingStorageMetrics recordingStorageMetrics) {
        this(ports, loadMonitor, capacity, recordingStorageMetrics, null, null);
    }

    MediaManagementHandler(
            MediaPorts ports,
            MediaNodeLoadMonitor loadMonitor,
            MediaRuntimeProperties.Capacity capacity,
            RecordingStorageMetrics recordingStorageMetrics,
            RecordSink recordSink,
            MediaPipeline mediaPipeline) {
        this.ports = ports;
        this.loadMonitor = loadMonitor;
        this.capacity = capacity;
        this.recordingStorageMetrics = recordingStorageMetrics;
        this.recordSink = recordSink;
        this.mediaPipeline = mediaPipeline;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        QueryStringDecoder uri = new QueryStringDecoder(request.uri());
        if (uri.path().equals("/recording/manual/start")
                || uri.path().equals("/recording/manual/stop")) {
            handleManualRecording(context, request, uri);
            return;
        }
        boolean managementEndpoint = uri.path().equals("/health")
                || uri.path().equals("/actuator/health")
                || uri.path().equals("/metrics/capacity")
                || uri.path().equals("/metrics/recording");
        HttpResponseStatus status = managementEndpoint ? HttpResponseStatus.OK : HttpResponseStatus.NOT_FOUND;
        var load = loadMonitor.snapshot();
        RecordingStorageSnapshot recording = managementEndpoint
                ? recordingSnapshot()
                : new RecordingStorageSnapshot(0, 0, 0);
        String body = managementEndpoint
                ? "{\"status\":\"UP\",\"ports\":{\"management\":" + ports.management()
                        + ",\"main\":" + ports.main() + ",\"sub\":" + ports.sub()
                        + ",\"playback\":" + ports.playback() + ",\"talkback\":" + ports.talkback()
                        + ",\"websocket\":" + ports.websocket() + "},\"currentStreams\":"
                        + load.currentStreams() + ",\"outboundBitsPerSecond\":"
                        + load.outboundBitsPerSecond() + ",\"maxStreams\":" + capacity.getMaxStreams()
                        + ",\"maxOutboundBitsPerSecond\":" + capacity.getMaxOutboundBitsPerSecond()
                        + ",\"recordingOccupiedBytes\":" + recording.occupiedBytes()
                        + ",\"recordingUsableBytes\":" + recording.usableBytes()
                        + ",\"recordingTotalBytes\":" + recording.totalBytes()
                        // 对接新设备时「画面是花的」几乎不给线索——这三个数是第一手诊断依据。
                        // undecided 不为零尤其要看：那说明有流是在没有依据的情况下按标准兜底跑的。
                        + ",\"jt1078SimBcd6Streams\":" + SimWidthStats.standardStreams()
                        + ",\"jt1078SimBcd10Streams\":" + SimWidthStats.extendedStreams()
                        + ",\"jt1078SimUndecidedStreams\":" + SimWidthStats.undecidedStreams() + "}"
                : "{\"status\":\"NOT_FOUND\"}";
        writeResponse(context, request, status, body);
    }

    private void handleManualRecording(
            ChannelHandlerContext context,
            FullHttpRequest request,
            QueryStringDecoder uri) {
        if (request.method() != HttpMethod.POST) {
            writeResponse(context, request, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    "{\"error\":\"METHOD_NOT_ALLOWED\"}");
            return;
        }
        if (recordSink == null || mediaPipeline == null) {
            writeResponse(context, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "{\"error\":\"RECORDING_CONTROL_UNAVAILABLE\"}");
            return;
        }
        try {
            StreamKey streamKey = streamKey(uri.parameters());
            boolean start = uri.path().endsWith("/start");
            AtomicBoolean changed = new AtomicBoolean();
            if (start) {
                boolean active = mediaPipeline.runIfActive(
                        streamKey, () -> changed.set(recordSink.startManual(streamKey)));
                if (!active) {
                    writeResponse(context, request, HttpResponseStatus.CONFLICT,
                            "{\"error\":\"STREAM_NOT_LIVE\"}");
                    return;
                }
            } else {
                changed.set(recordSink.stopManual(streamKey));
            }
            String state = recordSink.isRecording(streamKey) ? "recording" : "stopped";
            writeResponse(context, request, HttpResponseStatus.OK,
                    "{\"state\":\"" + state + "\",\"changed\":" + changed.get() + "}");
        } catch (IllegalArgumentException invalidRequest) {
            writeResponse(context, request, HttpResponseStatus.BAD_REQUEST,
                    "{\"error\":\"INVALID_RECORDING_REQUEST\"}");
        } catch (IllegalStateException unavailable) {
            writeResponse(context, request, HttpResponseStatus.CONFLICT,
                    "{\"error\":\"RECORDING_TRIGGER_DISABLED\"}");
        }
    }

    private static StreamKey streamKey(Map<String, List<String>> parameters) {
        String deviceId = singleParameter(parameters, "deviceId");
        int channel;
        try {
            channel = Integer.parseInt(singleParameter(parameters, "channel"));
        } catch (NumberFormatException invalidChannel) {
            throw new IllegalArgumentException("channel must be a number", invalidChannel);
        }
        StreamKind streamKind = StreamKind.fromWireValue(singleParameter(parameters, "streamKind"));
        return new StreamKey(deviceId, channel, streamKind);
    }

    private static String singleParameter(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException(name + " is required exactly once");
        }
        return values.getFirst();
    }

    private static void writeResponse(
            ChannelHandlerContext context,
            FullHttpRequest request,
            HttpResponseStatus status,
            String body) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        var future = context.writeAndFlush(response);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private RecordingStorageSnapshot recordingSnapshot() {
        if (recordingStorageMetrics == null) {
            return new RecordingStorageSnapshot(0, 0, 0);
        }
        try {
            return recordingStorageMetrics.snapshot();
        } catch (Exception failure) {
            LOGGER.error("Unable to collect recording storage metrics", failure);
            return new RecordingStorageSnapshot(0, 0, 0);
        }
    }
}
