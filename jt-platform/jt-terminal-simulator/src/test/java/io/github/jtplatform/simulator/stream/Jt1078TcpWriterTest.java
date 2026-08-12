package io.github.jtplatform.simulator.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.simulator.media.MediaFrame;
import io.github.jtplatform.simulator.media.MediaFrameType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.yzh.protocol.t1078.codec.Jt1078FragmentFlag;
import org.yzh.protocol.t1078.codec.Jt1078WireConstants;

class Jt1078TcpWriterTest {
    private static final String MOBILE_NO = "13800138000";

    @Test
    void writesSyntheticAudioAndVideoWithExpectedFrameIntervals() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] firstIFrame = h264((byte) 0x65, 1);
        byte[] pFrame = h264((byte) 0x41, 2);
        byte[] audio = filled(160, (byte) 0xd5);
        byte[] secondIFrame = h264((byte) 0x65, 3);

        try (Jt1078TcpWriter writer = Jt1078TcpWriter.forOutput(
                output, MOBILE_NO, 2, 1_400, 10)) {
            writer.write(new MediaFrame(MediaFrameType.VIDEO_I, 1_000, firstIFrame));
            writer.write(new MediaFrame(MediaFrameType.VIDEO_P, 1_040, pFrame));
            writer.write(new MediaFrame(MediaFrameType.AUDIO, 1_050, audio));
            writer.write(new MediaFrame(MediaFrameType.VIDEO_I, 1_200, secondIFrame));
            assertEquals(14, writer.nextSequence());
        }

        List<Packet> packets = packets(output.toByteArray());
        assertEquals(4, packets.size());
        assertPacket(packets.get(0), 10, Jt1078WireConstants.PT_H264,
                Jt1078WireConstants.VIDEO_I_FRAME, 1_000, 0, 0, firstIFrame);
        assertPacket(packets.get(1), 11, Jt1078WireConstants.PT_H264,
                Jt1078WireConstants.VIDEO_P_FRAME, 1_040, 40, 40, pFrame);
        assertPacket(packets.get(2), 12, Jt1078WireConstants.PT_G711A,
                Jt1078WireConstants.AUDIO_FRAME, 1_050, 0, 0, audio);
        assertPacket(packets.get(3), 13, Jt1078WireConstants.PT_H264,
                Jt1078WireConstants.VIDEO_I_FRAME, 1_200, 200, 160, secondIFrame);
        assertTrue(packets.stream().allMatch(Packet::marker));
        assertTrue(packets.stream().allMatch(packet ->
                packet.fragmentFlag() == Jt1078FragmentFlag.ATOMIC.wireValue()));
    }

    @Test
    void fragmentsAt1400BytesAndWrapsPacketSequence() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] accessUnit = new byte[2_801];
        for (int index = 0; index < accessUnit.length; index++) {
            accessUnit[index] = (byte) index;
        }

        try (Jt1078TcpWriter writer = Jt1078TcpWriter.forOutput(
                output, MOBILE_NO, 1, 1_400, 65_534)) {
            Jt1078TcpWriter.WriteResult result = writer.write(
                    new MediaFrame(MediaFrameType.VIDEO_I, 9_876, accessUnit));

            assertEquals(3, result.packetCount());
            assertEquals(1, result.nextSequence());
            assertEquals(1, writer.nextSequence());
        }

        List<Packet> packets = packets(output.toByteArray());
        assertEquals(List.of(65_534, 65_535, 0),
                packets.stream().map(Packet::sequence).toList());
        assertEquals(List.of(
                        Jt1078FragmentFlag.FIRST.wireValue(),
                        Jt1078FragmentFlag.MIDDLE.wireValue(),
                        Jt1078FragmentFlag.LAST.wireValue()),
                packets.stream().map(Packet::fragmentFlag).toList());
        assertEquals(List.of(false, false, true), packets.stream().map(Packet::marker).toList());
        assertTrue(packets.stream().allMatch(packet -> packet.timestamp() == 9_876));
        assertTrue(packets.stream().allMatch(packet -> packet.payloadType() == Jt1078WireConstants.PT_H264));
        assertArrayEquals(accessUnit, concatenatePayloads(packets));
    }

    @Test
    void connectsWritesAndReleasesALocalTcpSocket() throws Exception {
        CompletableFuture<byte[]> received = new CompletableFuture<>();
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread peer = Thread.ofVirtual().name("jt1078-test-peer").start(() -> {
                try (Socket socket = server.accept(); InputStream input = socket.getInputStream()) {
                    received.complete(input.readAllBytes());
                } catch (Throwable failure) {
                    received.completeExceptionally(failure);
                }
            });

            Jt1078TcpWriter writer = Jt1078TcpWriter.connect(
                    new MediaTarget("127.0.0.1", server.getLocalPort()),
                    MOBILE_NO,
                    3,
                    1_400,
                    Duration.ofSeconds(2));
            writer.write(new MediaFrame(MediaFrameType.AUDIO, 123, filled(160, (byte) 0x7f)));
            writer.close();
            writer.close();

            List<Packet> packets = packets(received.get(2, TimeUnit.SECONDS));
            assertEquals(1, packets.size());
            assertEquals(3, packets.getFirst().channel());
            assertEquals(Jt1078WireConstants.PT_G711A, packets.getFirst().payloadType());
            assertThrows(IOException.class, () -> writer.write(
                    new MediaFrame(MediaFrameType.AUDIO, 124, filled(160, (byte) 1))));
            peer.join(Duration.ofSeconds(2));
        }
    }

    private static void assertPacket(
            Packet packet,
            int sequence,
            int payloadType,
            int dataType,
            long timestamp,
            int lastIFrameInterval,
            int lastFrameInterval,
            byte[] payload) {
        assertEquals(sequence, packet.sequence());
        assertEquals(payloadType, packet.payloadType());
        assertEquals(dataType, packet.dataType());
        assertEquals(timestamp, packet.timestamp());
        assertEquals(lastIFrameInterval, packet.lastIFrameInterval());
        assertEquals(lastFrameInterval, packet.lastFrameInterval());
        assertArrayEquals(HexFormat.of().parseHex('0' + MOBILE_NO), packet.simBcd());
        assertArrayEquals(payload, packet.payload());
    }

    private static List<Packet> packets(byte[] bytes) {
        List<Packet> packets = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            if (bytes.length - offset < Jt1078WireConstants.AUDIO_HEADER_LENGTH) {
                throw new AssertionError("Incomplete JT/T 1078 header at offset " + offset);
            }
            ByteBuffer header = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
            assertEquals(Jt1078WireConstants.MAGIC, header.getInt());
            header.get();
            int markerAndPayloadType = Byte.toUnsignedInt(header.get());
            int sequence = Short.toUnsignedInt(header.getShort());
            byte[] simBcd = new byte[6];
            header.get(simBcd);
            int channel = Byte.toUnsignedInt(header.get());
            int typeAndFragment = Byte.toUnsignedInt(header.get());
            int dataType = typeAndFragment >>> 4;
            int fragmentFlag = typeAndFragment & 0x0f;
            long timestamp = header.getLong();
            int lastIFrameInterval = 0;
            int lastFrameInterval = 0;
            if (dataType <= Jt1078WireConstants.VIDEO_B_FRAME) {
                lastIFrameInterval = Short.toUnsignedInt(header.getShort());
                lastFrameInterval = Short.toUnsignedInt(header.getShort());
            }
            int payloadLength = Short.toUnsignedInt(header.getShort());
            int headerLength = dataType <= Jt1078WireConstants.VIDEO_B_FRAME
                    ? Jt1078WireConstants.VIDEO_HEADER_LENGTH
                    : Jt1078WireConstants.AUDIO_HEADER_LENGTH;
            int packetEnd = offset + headerLength + payloadLength;
            if (packetEnd > bytes.length) {
                throw new AssertionError("Incomplete JT/T 1078 payload at offset " + offset);
            }
            packets.add(new Packet(
                    sequence,
                    markerAndPayloadType & 0x7f,
                    (markerAndPayloadType & Jt1078WireConstants.MARKER_MASK) != 0,
                    simBcd,
                    channel,
                    dataType,
                    fragmentFlag,
                    timestamp,
                    lastIFrameInterval,
                    lastFrameInterval,
                    Arrays.copyOfRange(bytes, offset + headerLength, packetEnd)));
            offset = packetEnd;
        }
        return List.copyOf(packets);
    }

    private static byte[] concatenatePayloads(List<Packet> packets) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Packet packet : packets) {
            output.write(packet.payload());
        }
        return output.toByteArray();
    }

    private static byte[] h264(byte sliceHeader, int value) {
        return new byte[] {
                0, 0, 0, 1, 0x09, 0x10,
                0, 0, 0, 1, sliceHeader, (byte) value
        };
    }

    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private record Packet(
            int sequence,
            int payloadType,
            boolean marker,
            byte[] simBcd,
            int channel,
            int dataType,
            int fragmentFlag,
            long timestamp,
            int lastIFrameInterval,
            int lastFrameInterval,
            byte[] payload) {
    }
}
