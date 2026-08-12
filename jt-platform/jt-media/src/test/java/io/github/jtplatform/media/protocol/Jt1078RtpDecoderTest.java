package io.github.jtplatform.media.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.StreamKind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Jt1078RtpDecoderTest {
    @ParameterizedTest
    @MethodSource("streamKinds")
    void decodesHeaderAndClassifiesPacketByIngressPort(StreamKind streamKind) {
        EmbeddedChannel channel = new EmbeddedChannel(new Jt1078RtpDecoder(streamKind, 65_535));
        byte[] payload = {1, 2, 3, 4};

        assertTrue(channel.writeInbound(packet(Jt1078Constants.VIDEO_I_FRAME,
                FragmentFlag.ATOMIC, Jt1078Constants.PT_H264, payload)));
        RtpPacket decoded = channel.readInbound();

        assertEquals("13800138000", decoded.header().deviceId());
        assertEquals(2, decoded.header().channel());
        assertEquals(7, decoded.header().sequence());
        assertEquals(123_456_789L, decoded.header().timestamp());
        assertEquals(streamKind, decoded.streamKind());
        assertArrayEquals(payload, decoded.payload());
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void waitsForCompletePacketAcrossTcpReads() {
        EmbeddedChannel channel = new EmbeddedChannel(new Jt1078RtpDecoder(StreamKind.MAIN, 65_535));
        ByteBuf encoded = packet(Jt1078Constants.AUDIO_FRAME,
                FragmentFlag.ATOMIC, Jt1078Constants.PT_G711A, new byte[] {9, 8, 7});
        ByteBuf first = encoded.readRetainedSlice(20);
        ByteBuf second = encoded.readRetainedSlice(encoded.readableBytes());
        encoded.release();

        assertFalse(channel.writeInbound(first));
        assertNull(channel.readInbound());
        assertTrue(channel.writeInbound(second));
        RtpPacket decoded = channel.readInbound();
        assertEquals(Jt1078Constants.AUDIO_FRAME, decoded.header().dataType());
        assertArrayEquals(new byte[] {9, 8, 7}, decoded.payload());
        channel.finishAndReleaseAll();
    }

    @Test
    void resynchronizesAfterUnrelatedBytes() {
        EmbeddedChannel channel = new EmbeddedChannel(new Jt1078RtpDecoder(StreamKind.PLAYBACK, 65_535));
        ByteBuf input = Unpooled.buffer();
        input.writeBytes(new byte[] {10, 11, 12, 13, 14});
        input.writeBytes(packet(Jt1078Constants.TRANSPARENT_DATA,
                FragmentFlag.ATOMIC, 0, new byte[] {42}));

        assertTrue(channel.writeInbound(input));
        RtpPacket decoded = channel.readInbound();
        assertEquals(StreamKind.PLAYBACK, decoded.streamKind());
        assertEquals(Jt1078Constants.TRANSPARENT_DATA, decoded.header().dataType());
        assertArrayEquals(new byte[] {42}, decoded.payload());
        channel.finishAndReleaseAll();
    }

    private static Stream<Arguments> streamKinds() {
        return Stream.of(StreamKind.values()).map(Arguments::of);
    }

    private static ByteBuf packet(int dataType, FragmentFlag flag, int payloadType, byte[] payload) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeBytes(Jt1078Constants.MAGIC);
        buffer.writeByte(0x81);
        buffer.writeByte(0x80 | payloadType);
        buffer.writeShort(7);
        buffer.writeBytes(new byte[] {0x01, 0x38, 0x00, 0x13, (byte) 0x80, 0x00});
        buffer.writeByte(2);
        buffer.writeByte((dataType << 4) | flag.wireValue());
        if (dataType != Jt1078Constants.TRANSPARENT_DATA) {
            buffer.writeLong(123_456_789L);
        }
        if (dataType <= Jt1078Constants.VIDEO_B_FRAME) {
            buffer.writeShort(40);
            buffer.writeShort(20);
        }
        buffer.writeShort(payload.length);
        buffer.writeBytes(payload);
        return buffer;
    }
}
