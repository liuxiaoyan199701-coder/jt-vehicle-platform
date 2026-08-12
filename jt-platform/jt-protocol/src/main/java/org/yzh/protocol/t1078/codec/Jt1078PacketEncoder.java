package org.yzh.protocol.t1078.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class Jt1078PacketEncoder {
    public byte[] encode(Jt1078PacketHeader header, byte[] payload) {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > Jt1078WireConstants.MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("payload length must be in range 0..65535");
        }

        ByteBuffer packet = ByteBuffer.allocate(header.encodedLength() + payload.length)
                .order(ByteOrder.BIG_ENDIAN);
        packet.putInt(Jt1078WireConstants.MAGIC);
        packet.put((byte) Jt1078WireConstants.FIXED_RTP_HEADER);
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

    private static byte[] encodeDeviceId(String deviceId) {
        Jt1078PacketHeader.validateDeviceId(deviceId);
        String padded = "0".repeat(12 - deviceId.length()) + deviceId;
        byte[] encoded = new byte[6];
        for (int index = 0; index < encoded.length; index++) {
            int high = padded.charAt(index * 2) - '0';
            int low = padded.charAt(index * 2 + 1) - '0';
            encoded[index] = (byte) ((high << 4) | low);
        }
        return encoded;
    }
}
