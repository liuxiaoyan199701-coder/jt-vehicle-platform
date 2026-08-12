package io.github.jtplatform.media.netty;

import io.github.jtplatform.media.pipeline.MediaPipeline;
import io.github.jtplatform.media.pipeline.StreamOwnershipRejectedException;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.protocol.RtpPacket;
import io.github.jtplatform.media.talkback.TalkbackService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class MediaIngressHandler extends SimpleChannelInboundHandler<RtpPacket> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaIngressHandler.class);

    private final MediaPipeline pipeline;
    private final String mediaInstanceId;
    private final TalkbackService talkbackService;
    private final Object ingressSession = new Object();

    MediaIngressHandler(MediaPipeline pipeline, String mediaInstanceId) {
        this(pipeline, mediaInstanceId, null);
    }

    MediaIngressHandler(MediaPipeline pipeline, String mediaInstanceId, TalkbackService talkbackService) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.mediaInstanceId = requireText(mediaInstanceId, "mediaInstanceId");
        this.talkbackService = talkbackService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, RtpPacket packet) {
        try {
            List<MediaFrame> frames = pipeline.accept(ingressSession, packet);
            if (talkbackService != null
                    && packet.streamKind() == io.github.jtplatform.common.model.StreamKind.TALKBACK
                    && !frames.isEmpty()) {
                talkbackService.registerDeviceChannel(
                        packet.streamKey(), context.channel(), packet.header());
            }
        } catch (StreamOwnershipRejectedException rejected) {
            LOGGER.warn("Closing JT/T 1078 ingest connection for stream {} on media instance {}: "
                            + "StreamRegistry entry is missing, inactive, or owned by another instance; remote={}",
                    rejected.streamKey().externalId(), mediaInstanceId, context.channel().remoteAddress());
            context.close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        pipeline.closeIngress(ingressSession);
        super.channelInactive(context);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOGGER.error("JT/T 1078 ingest connection failed: {}", context.channel().remoteAddress(), cause);
        context.close();
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
