package org.yzh.protocol.t1078.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class Jt1078PacketEncoder {

    private final Jt1078SimFormat simFormat;

    /** 默认按 1078-2016 标准编码，行为与本类历来一致。 */
    public Jt1078PacketEncoder() {
        this(Jt1078SimFormat.STANDARD);
    }

    public Jt1078PacketEncoder(Jt1078SimFormat simFormat) {
        this.simFormat = Objects.requireNonNull(simFormat, "simFormat");
    }

    public Jt1078SimFormat simFormat() {
        return simFormat;
    }

    public byte[] encode(Jt1078PacketHeader header, byte[] payload) {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > Jt1078WireConstants.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("payload length must be in range 0..65535");
        }

        ByteBuffer packet = ByteBuffer
                .allocate(header.encodedLength() + simFormat.offsetShift() + payload.length)
                .order(ByteOrder.BIG_ENDIAN);
        packet.putInt(Jt1078WireConstants.MAGIC);
        // 第 4 字节同时承载 SIM 宽度标志：0x81 标准、0x91 扩展。
        // 野生形态刻意仍写 0x81——那正是它「不做标记」的本质。
        packet.put((byte) simFormat.rtpHeaderByte());
        packet.put((byte) ((header.marker() ? Jt1078WireConstants.MARKER_MASK : 0)
                | header.payloadType()));
        packet.putShort((short) header.sequence());
        packet.put(encodeDeviceId(header.deviceId()));
        packet.put((byte) header.channel());
        packet.put((byte) ((header.dataType() << 4) | header.fragmentFlag().wireValue()));
        if (header.dataType() != Jt1078WireConstants.TRANSPARENT_DATA) {
            packet.putLong(header.timestamp());
        }
        if (header.video()) {
            packet.putShort((short) header.lastIFrameInterval());
            packet.putShort((short) header.lastFrameInterval());
        }
        packet.putShort((short) payload.length);
        packet.put(payload);
        return packet.array();
    }

    public Jt1078PacketBatch encodeFrame(
            Jt1078Frame frame, int maxPayloadLength, int firstSequence) {
        Objects.requireNonNull(frame, "frame");
        if (maxPayloadLength < 1
                || maxPayloadLength > Jt1078WireConstants.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("maxPayloadLength must be in range 1..65535");
        }
        Jt1078PacketHeader.validateSequence(firstSequence);

        byte[] framePayload = frame.payload();
        int packetCount = Math.max(1,
                (int) (((long) framePayload.length + maxPayloadLength - 1) / maxPayloadLength));
        List<byte[]> packets = new ArrayList<>(packetCount);
        int sequence = firstSequence;
        for (int index = 0; index < packetCount; index++) {
            int offset = index * maxPayloadLength;
            int end = Math.min(framePayload.length, offset + maxPayloadLength);
            byte[] packetPayload = Arrays.copyOfRange(framePayload, offset, end);
            Jt1078FragmentFlag fragmentFlag = fragmentFlag(index, packetCount);
            boolean marker = fragmentFlag == Jt1078FragmentFlag.ATOMIC
                    || fragmentFlag == Jt1078FragmentFlag.LAST;
            Jt1078PacketHeader header = new Jt1078PacketHeader(
                    frame.payloadType(),
                    marker,
                    sequence,
                    frame.deviceId(),
                    frame.channel(),
                    frame.dataType(),
                    fragmentFlag,
                    frame.timestamp(),
                    frame.lastIFrameInterval(),
                    frame.lastFrameInterval());
            packets.add(encode(header, packetPayload));
            sequence = nextSequence(sequence);
        }
        return new Jt1078PacketBatch(packets, sequence);
    }

    public static int nextSequence(int sequence) {
        Jt1078PacketHeader.validateSequence(sequence);
        return (sequence + 1) & 0xffff;
    }

    private static Jt1078FragmentFlag fragmentFlag(int index, int packetCount) {
        if (packetCount == 1) {
            return Jt1078FragmentFlag.ATOMIC;
        }
        if (index == 0) {
            return Jt1078FragmentFlag.FIRST;
        }
        if (index == packetCount - 1) {
            return Jt1078FragmentFlag.LAST;
        }
        return Jt1078FragmentFlag.MIDDLE;
    }

    /**
     * 按当前形态编码设备号。
     *
     * <p>标准形态只有 12 位空间，20 位号码取**末 12 位**——左侧是填充零，真正的号码在右侧。
     * 对大陆 11 位手机号这是无损的；但若厂商把设备序列号之类塞进 20 位字段，截断就是有损的，
     * 那种场景应当改用扩展形态。
     */
    private byte[] encodeDeviceId(String deviceId) {
        Jt1078PacketHeader.validateDeviceId(deviceId);
        int digits = simFormat.simDigits();
        String significant = deviceId.length() > digits
                ? deviceId.substring(deviceId.length() - digits)
                : deviceId;
        String padded = "0".repeat(digits - significant.length()) + significant;
        byte[] encoded = new byte[simFormat.simBytes()];
        for (int index = 0; index < encoded.length; index++) {
            int high = padded.charAt(index * 2) - '0';
            int low = padded.charAt(index * 2 + 1) - '0';
            encoded[index] = (byte) ((high << 4) | low);
        }
        return encoded;
    }
}
