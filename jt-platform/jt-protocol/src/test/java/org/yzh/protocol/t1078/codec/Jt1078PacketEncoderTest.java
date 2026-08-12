package org.yzh.protocol.t1078.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class Jt1078PacketEncoderTest {
    private static final Jt1078PacketEncoder ENCODER = new Jt1078PacketEncoder();

    @Test
    void encodesAnAtomicG711APacketWithPaddedBcdDeviceId() {
        Jt1078PacketHeader header = new Jt1078PacketHeader(
                Jt1078WireConstants.PT_G711A,
                true,
                0xabcd,
                "13800138000",
                2,
                Jt1078WireConstants.AUDIO_FRAME,
                Jt1078FragmentFlag.ATOMIC,
                1_786_365_296_789L,
                0,
                0);

        byte[] packet = ENCODER.encode(header, new byte[] {(byte) 0xd5, (byte) 0xc7, (byte) 0xfa});

        assertEquals(
                "303163648186abcd01380013800002300000019febab40950003d5c7fa",
                HexFormat.of().formatHex(packet));
    }

    @Test
    void encodesVideoTimingFieldsAndAnUnsetMarkerBit() {
        Jt1078PacketHeader header = new Jt1078PacketHeader(
                Jt1078WireConstants.PT_H264,
                false,
                0x1234,
                "123456789012",
                15,
                Jt1078WireConstants.VIDEO_I_FRAME,
                Jt1078FragmentFlag.FIRST,
                0x0102030405060708L,
                0x1122,
                0x3344);

        byte[] packet = ENCODER.encode(header, new byte[] {0x65, 0x00});

        assertEquals(
                "30316364816212341234567890120f0101020304050607081122334400026500",
                HexFormat.of().formatHex(packet));
        assertEquals(Jt1078WireConstants.VIDEO_HEADER_LENGTH + 2, packet.length);
        assertFalse(marker(packet));
    }

    @Test
    void validatesDeviceIdentifiersBeforeBcdEncoding() {
        assertThrows(IllegalArgumentException.class, () -> audioHeader(""));
        assertThrows(IllegalArgumentException.class, () -> audioHeader("13800A38000"));
        assertThrows(IllegalArgumentException.class, () -> audioHeader("1234567890123"));
    }

    @Test
    void acceptsTheLargestWirePayloadAndRejectsAnOversizedPayload() {
        byte[] largestPayload = new byte[Jt1078WireConstants.MAX_PAYLOAD_LENGTH];

        byte[] packet = ENCODER.encode(audioHeader("1"), largestPayload);

        assertEquals(Jt1078WireConstants.AUDIO_HEADER_LENGTH + largestPayload.length, packet.length);
        assertEquals(0xffff, Short.toUnsignedInt(ByteBuffer.wrap(packet, 24, 2).getShort()));
        assertThrows(IllegalArgumentException.class,
                () -> ENCODER.encode(audioHeader("1"), new byte[65_536]));
    }

    @Test
    void fragmentsFramesAndWrapsTheSequenceNumber() {
        Jt1078Frame frame = new Jt1078Frame(
                "13800138000",
                2,
                Jt1078WireConstants.AUDIO_FRAME,
                Jt1078WireConstants.PT_G711A,
                42,
                0,
                0,
                new byte[] {1, 2, 3, 4, 5});

        Jt1078PacketBatch batch = ENCODER.encodeFrame(frame, 2, 65_534);
        List<byte[]> packets = batch.packets();

        assertEquals(3, packets.size());
        assertPacket(packets.get(0), 65_534, Jt1078FragmentFlag.FIRST, false,
                new byte[] {1, 2});
        assertPacket(packets.get(1), 65_535, Jt1078FragmentFlag.MIDDLE, false,
                new byte[] {3, 4});
        assertPacket(packets.get(2), 0, Jt1078FragmentFlag.LAST, true,
                new byte[] {5});
        assertEquals(1, batch.nextSequence());
    }

    @Test
    void emitsAnAtomicPacketForAnEmptyFrame() {
        Jt1078Frame frame = new Jt1078Frame(
                "1",
                1,
                Jt1078WireConstants.AUDIO_FRAME,
                Jt1078WireConstants.PT_G711A,
                42,
                0,
                0,
                new byte[0]);

        Jt1078PacketBatch batch = ENCODER.encodeFrame(frame, 1_400, 65_535);
        byte[] packet = batch.packets().getFirst();

        assertEquals(1, batch.packets().size());
        assertPacket(packet, 65_535, Jt1078FragmentFlag.ATOMIC, true, new byte[0]);
        assertEquals(0, batch.nextSequence());
    }

    @Test
    void frameAndBatchDoNotExposeMutablePacketStorage() {
        byte[] source = {1, 2};
        Jt1078Frame frame = new Jt1078Frame(
                "1",
                1,
                Jt1078WireConstants.AUDIO_FRAME,
                Jt1078WireConstants.PT_G711A,
                0,
                0,
                0,
                source);
        source[0] = 9;
        byte[] exposedFramePayload = frame.payload();
        exposedFramePayload[1] = 9;

        assertArrayEquals(new byte[] {1, 2}, frame.payload());

        byte[] packet = ENCODER.encode(audioHeader("1"), new byte[] {3});
        Jt1078PacketBatch batch = new Jt1078PacketBatch(List.of(packet), 1);
        packet[0] = 0;
        byte[] exposedPacket = batch.packets().getFirst();
        exposedPacket[1] = 0;

        assertEquals(0x30, Byte.toUnsignedInt(batch.packets().getFirst()[0]));
        assertEquals(0x31, Byte.toUnsignedInt(batch.packets().getFirst()[1]));
    }

    private static Jt1078PacketHeader audioHeader(String deviceId) {
        return new Jt1078PacketHeader(
                Jt1078WireConstants.PT_G711A,
                true,
                0,
                deviceId,
                1,
                Jt1078WireConstants.AUDIO_FRAME,
                Jt1078FragmentFlag.ATOMIC,
                0,
                0,
                0);
    }

    private static void assertPacket(
            byte[] packet,
            int sequence,
            Jt1078FragmentFlag fragmentFlag,
            boolean marker,
            byte[] expectedPayload) {
        assertEquals(sequence, Short.toUnsignedInt(ByteBuffer.wrap(packet, 6, 2).getShort()));
        assertEquals(fragmentFlag.wireValue(), packet[15] & 0x0f);
        assertEquals(marker, marker(packet));
        assertEquals(42, ByteBuffer.wrap(packet, 16, 8).getLong());
        assertEquals(expectedPayload.length,
                Short.toUnsignedInt(ByteBuffer.wrap(packet, 24, 2).getShort()));
        assertArrayEquals(expectedPayload,
                java.util.Arrays.copyOfRange(packet, Jt1078WireConstants.AUDIO_HEADER_LENGTH, packet.length));
    }

    private static boolean marker(byte[] packet) {
        return (packet[5] & Jt1078WireConstants.MARKER_MASK) != 0;
    }
}
