package io.github.jtplatform.simulator.signal;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.codec.MultiPacketDecoder;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0801;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拍照上传的编解码回环：按 SignalClient.uploadPhoto 的方式构建一条完整 T0801
 * （含整张 JPEG），编码器自动分包，网关侧的 MultiPacketDecoder 逐个收帧后
 * 重组出完整消息。
 */
class PhotoSubpackageRoundTripTest {

    private final Jt808MessageCodec codec = new Jt808MessageCodec();

    @Test
    void autoSplitUploadReassemblesToTheFullJpeg() {
        int jpegLength = 5000;
        byte[] jpeg = new byte[jpegLength];
        for (int i = 0; i < jpeg.length; i++) {
            jpeg[i] = (byte) (i % 251);
        }

        T0801 upload = new T0801()
                .setId(0x01020304)
                .setType(0)
                .setFormat(0)
                .setEvent(0)
                .setChannelId(1)
                .setLocation(zeroLocation())
                .setPacket(Unpooled.wrappedBuffer(jpeg));
        prepare(upload, 6);

        byte[] encoded = codec.encode(upload);
        List<ByteBuf> frames = frames(encoded);

        MultiPacketDecoder decoder = new MultiPacketDecoder("org.yzh.protocol");
        try {
            T0801 reassembled = null;
            for (int i = 0; i < frames.size(); i++) {
                JTMessage decoded = decoder.decode(frames.get(i));
                if (i < frames.size() - 1) {
                    // 未收齐时上游只返回「仅头部」的空壳消息（网关的
                    // SignalMultiPacketDecoder 会将其过滤为 null），体字段为空
                    assertTrue(decoded == null || ((T0801) decoded).getPacket() == null,
                            "非最后分包不应携带消息体");
                } else {
                    reassembled = (T0801) decoded;
                }
            }
            assertNotNull(reassembled);
            assertEquals(0x01020304, reassembled.getId());
            assertEquals(1, reassembled.getChannelId());
            assertEquals(jpegLength, reassembled.getPacket().readableBytes());
            byte[] actual = new byte[jpegLength];
            reassembled.getPacket().getBytes(0, actual);
            for (int i = 0; i < jpegLength; i++) {
                assertEquals(jpeg[i], actual[i], "JPEG 第 " + i + " 字节不一致");
            }
        } finally {
            frames.forEach(io.netty.util.ReferenceCountUtil::safeRelease);
        }
    }

    /** 与 SignalClient.prepare 相同的编码前字段设置 */
    private static <T extends JTMessage> T prepare(T message, int serial) {
        message.setMessageId(message.reflectMessageId());
        message.setClientId("00000000138000000000");
        message.setSerialNo(serial);
        message.setEncryption(0);
        message.setSubpackage(false);
        message.setReserved(false);
        message.setProtocolVersion(1);
        message.setVersion(true);
        return message;
    }

    private static T0200 zeroLocation() {
        return new T0200().setDeviceTime(LocalDateTime.of(2000, 1, 1, 0, 0, 0));
    }

    /** 从一次 encode 的输出里切出所有 0x7e 定界的帧 */
    private static List<ByteBuf> frames(byte[] encoded) {
        List<ByteBuf> result = new ArrayList<>();
        ByteBuf buf = Unpooled.wrappedBuffer(encoded);
        int searchFrom = 0;
        while (searchFrom < buf.writerIndex()) {
            int start = buf.indexOf(searchFrom, buf.writerIndex(), (byte) 0x7e);
            if (start < 0) {
                break;
            }
            int end = buf.indexOf(start + 1, buf.writerIndex(), (byte) 0x7e);
            if (end < 0) {
                break;
            }
            result.add(buf.retainedSlice(start, end - start + 1));
            searchFrom = end + 1;
        }
        return result;
    }
}
