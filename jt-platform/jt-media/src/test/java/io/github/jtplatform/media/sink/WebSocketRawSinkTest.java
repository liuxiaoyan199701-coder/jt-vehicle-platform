package io.github.jtplatform.media.sink;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

class WebSocketRawSinkTest {
    private static final StreamKey STREAM = new StreamKey("13800138000", 2, StreamKind.MAIN);

    @Test
    void writesStateBeforeFixedHeaderRawFrames() {
        WebSocketRawSink sink = new WebSocketRawSink();
        EmbeddedChannel subscriber = new EmbeddedChannel();
        sink.subscribe(STREAM, subscriber);
        subscriber.runPendingTasks();

        TextWebSocketFrame waking = subscriber.readOutbound();
        assertTrue(waking.text().contains("waking"));
        waking.release();

        sink.accept(frame(MediaFrameType.VIDEO_KEY, new byte[] {10, 11, 12}));
        subscriber.runPendingTasks();
        TextWebSocketFrame live = subscriber.readOutbound();
        assertTrue(live.text().contains("live"));
        live.release();
        BinaryWebSocketFrame binary = subscriber.readOutbound();
        assertEquals(11, binary.content().readableBytes());
        byte[] encoded = new byte[binary.content().readableBytes()];
        binary.content().readBytes(encoded);
        assertArrayEquals(new byte[] {'J', 'T', '7', '8', 0, 2, 0, 0, 10, 11, 12}, encoded);
        binary.release();
        assertEquals(11, sink.outboundBytes());
        subscriber.finishAndReleaseAll();
    }

    @Test
    void newSubscriberReceivesCachedParametersAndGopHead() {
        WebSocketRawSink sink = new WebSocketRawSink();
        sink.accept(frame(MediaFrameType.SPS, new byte[] {1}));
        sink.accept(frame(MediaFrameType.PPS, new byte[] {2}));
        sink.accept(frame(MediaFrameType.VIDEO_KEY, new byte[] {3}));
        EmbeddedChannel subscriber = new EmbeddedChannel();

        sink.subscribe(STREAM, subscriber);
        subscriber.runPendingTasks();

        TextWebSocketFrame state = subscriber.readOutbound();
        assertTrue(state.text().contains("live"));
        state.release();
        assertFrameType(subscriber, MediaFrameType.SPS);
        assertFrameType(subscriber, MediaFrameType.PPS);
        assertFrameType(subscriber, MediaFrameType.VIDEO_KEY);
        subscriber.finishAndReleaseAll();
    }

    private static void assertFrameType(EmbeddedChannel channel, MediaFrameType expected) {
        BinaryWebSocketFrame frame = assertInstanceOf(BinaryWebSocketFrame.class, channel.readOutbound());
        assertEquals(expected.wireValue(), frame.content().getUnsignedByte(4));
        frame.release();
    }

    private static MediaFrame frame(MediaFrameType type, byte[] payload) {
        return new MediaFrame(STREAM, type, MediaCodec.H264, 10, payload);
    }
}
