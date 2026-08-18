package io.github.jtplatform.media.protocol;

import io.github.jtplatform.common.model.StreamKind;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Jt1078RtpDecoder extends ByteToMessageDecoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(Jt1078RtpDecoder.class);

    private final StreamKind streamKind;
    private final int maxPayloadBytes;
    /**
     * 本连接的 SIM 宽度，判定一次后复用。
     *
     * <p>Netty 的解码器每条连接一个实例（见 {@code MediaNodeServer} 的 pipeline 装配），
     * 所以放在这里就等于连接级缓存，不必再往 channel attribute 里塞一层。
     */
    private final SimWidthResolver simWidth = new SimWidthResolver();

    public Jt1078RtpDecoder(StreamKind streamKind, int maxPayloadBytes) {
        this.streamKind = Objects.requireNonNull(streamKind, "streamKind");
        if (maxPayloadBytes < 1 || maxPayloadBytes > 0xffff) {
            throw new IllegalArgumentException("maxPayloadBytes must be in range 1..65535");
        }
        this.maxPayloadBytes = maxPayloadBytes;
    }

    /** 本连接判定出的 SIM 宽度，未判定时为 null。供指标统计。 */
    public SimWidth simWidth() {
        return simWidth.resolved();
    }

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        if (input.readableBytes() < Jt1078Constants.MAGIC.length) {
            return;
        }

        int magicOffset = findMagic(input);
        if (magicOffset < 0) {
            input.skipBytes(Math.max(0, input.readableBytes() - (Jt1078Constants.MAGIC.length - 1)));
            return;
        }
        if (magicOffset > 0) {
            LOGGER.debug("Discarding {} bytes before JT/T 1078 magic", magicOffset);
            input.skipBytes(magicOffset);
        }
        if (input.readableBytes() < Jt1078Constants.COMMON_HEADER_LENGTH) {
            return;
        }

        int start = input.readerIndex();
        // 判定一次即缓存。数据不足以判定时返回 null，等更多字节再说——
        // 用半个头拍脑袋定下来，之后整条流都会按错误偏移解析。
        SimWidth width = simWidth.resolve(input, start);
        if (width == null) {
            return;
        }
        int shift = width.offsetShift();

        int typeAndFragment = input.getUnsignedByte(start + 15 + shift);
        int dataType = (typeAndFragment >>> 4) & 0x0f;
        int headerLength;
        try {
            headerLength = headerLength(dataType) + shift;
            FragmentFlag.fromWireValue(typeAndFragment & 0x0f);
        } catch (IllegalArgumentException invalidHeader) {
            LOGGER.warn("Discarding invalid JT/T 1078 header: {}", invalidHeader.getMessage());
            input.skipBytes(1);
            return;
        }
        if (input.readableBytes() < headerLength) {
            return;
        }

        int bodyLength = input.getUnsignedShort(start + headerLength - Short.BYTES);
        if (bodyLength > maxPayloadBytes) {
            LOGGER.warn("Discarding JT/T 1078 packet with body length {} above limit {}",
                    bodyLength, maxPayloadBytes);
            input.skipBytes(1);
            return;
        }
        if (input.readableBytes() < headerLength + bodyLength) {
            return;
        }

        try {
            int version = input.getUnsignedByte(start + 4);
            int payloadType = input.getUnsignedByte(start + 5) & 0x7f;
            int sequence = input.getUnsignedShort(start + 6);
            // SIM 字段之后的每一个偏移都要平移 shift 字节——漏掉任何一处，
            // 解出来的都是相邻字段的字节拼成的随机数，而且不会报错。
            String deviceId = decodeBcd(input, start + 8, width.bytes());
            int channel = input.getUnsignedByte(start + 14 + shift);
            FragmentFlag fragmentFlag = FragmentFlag.fromWireValue(typeAndFragment & 0x0f);
            long timestamp = dataType == Jt1078Constants.TRANSPARENT_DATA
                    ? 0L : input.getLong(start + 16 + shift);
            int lastIFrameInterval = dataType <= Jt1078Constants.VIDEO_B_FRAME
                    ? input.getUnsignedShort(start + 24 + shift) : 0;
            int lastFrameInterval = dataType <= Jt1078Constants.VIDEO_B_FRAME
                    ? input.getUnsignedShort(start + 26 + shift) : 0;
            Jt1078Header header = new Jt1078Header(
                    version,
                    payloadType,
                    sequence,
                    deviceId,
                    channel,
                    dataType,
                    fragmentFlag,
                    timestamp,
                    lastIFrameInterval,
                    lastFrameInterval,
                    bodyLength);

            input.skipBytes(headerLength);
            byte[] payload = new byte[bodyLength];
            input.readBytes(payload);
            output.add(new RtpPacket(header, streamKind, payload));
        } catch (IllegalArgumentException invalidPacket) {
            LOGGER.warn("Discarding invalid JT/T 1078 packet: {}", invalidPacket.getMessage());
            input.readerIndex(start + 1);
        }
    }

    private static int headerLength(int dataType) {
        return switch (dataType) {
            case Jt1078Constants.VIDEO_I_FRAME,
                    Jt1078Constants.VIDEO_P_FRAME,
                    Jt1078Constants.VIDEO_B_FRAME -> Jt1078Constants.VIDEO_HEADER_LENGTH;
            case Jt1078Constants.AUDIO_FRAME -> Jt1078Constants.AUDIO_HEADER_LENGTH;
            case Jt1078Constants.TRANSPARENT_DATA -> Jt1078Constants.TRANSPARENT_HEADER_LENGTH;
            default -> throw new IllegalArgumentException("Unsupported JT/T 1078 data type: " + dataType);
        };
    }

    private static int findMagic(ByteBuf input) {
        int start = input.readerIndex();
        int end = input.writerIndex() - Jt1078Constants.MAGIC.length;
        for (int index = start; index <= end; index++) {
            if ((input.getByte(index) & 0x7f) == Jt1078Constants.MAGIC[0]
                    && input.getByte(index + 1) == Jt1078Constants.MAGIC[1]
                    && input.getByte(index + 2) == Jt1078Constants.MAGIC[2]
                    && input.getByte(index + 3) == Jt1078Constants.MAGIC[3]) {
                return index - start;
            }
        }
        return -1;
    }

    private static String decodeBcd(ByteBuf input, int offset, int length) {
        StringBuilder value = new StringBuilder(length * 2);
        for (int index = 0; index < length; index++) {
            int current = input.getUnsignedByte(offset + index);
            appendBcdDigit(value, current >>> 4);
            appendBcdDigit(value, current & 0x0f);
        }
        String decoded = value.toString().replaceFirst("^0+(?!$)", "");
        if (decoded.isBlank()) {
            throw new IllegalArgumentException("SIM BCD value is empty");
        }
        return decoded;
    }

    private static void appendBcdDigit(StringBuilder value, int digit) {
        if (digit <= 9) {
            value.append((char) ('0' + digit));
            return;
        }
        if (digit != 0x0f) {
            throw new IllegalArgumentException("SIM contains a non-BCD nibble: " + digit);
        }
    }
}
