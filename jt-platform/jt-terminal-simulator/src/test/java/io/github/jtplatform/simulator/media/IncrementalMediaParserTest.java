package io.github.jtplatform.simulator.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncrementalMediaParserTest {
    @Test
    void extractsMjpegFramesAcrossEveryMarkerBoundary() {
        MjpegStreamParser parser = new MjpegStreamParser(128);
        byte[] stream = {9, (byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, 2,
                (byte) 0xff, (byte) 0xd9, 7,
                (byte) 0xff, (byte) 0xd8, 3, 4, (byte) 0xff, (byte) 0xd9};
        List<byte[]> frames = new ArrayList<>();

        for (byte value : stream) {
            frames.addAll(parser.accept(new byte[] {value}));
        }

        assertEquals(2, frames.size());
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, 2,
                (byte) 0xff, (byte) 0xd9}, frames.get(0));
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xd8, 3, 4,
                (byte) 0xff, (byte) 0xd9}, frames.get(1));
    }

    @Test
    void rejectsOversizedMjpegFrameAndCanRecover() {
        MjpegStreamParser parser = new MjpegStreamParser(4);

        assertThrows(IllegalStateException.class, () -> parser.accept(new byte[] {
                (byte) 0xff, (byte) 0xd8, 1, 2, 3}));
        List<byte[]> recovered = parser.accept(new byte[] {
                (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});

        assertEquals(1, recovered.size());
    }

    @Test
    void groupsAnnexBByAudAcrossArbitraryReadsAndDetectsIdr() {
        byte[] key = accessUnit(0x65, new byte[] {0x67, 0x42, 0x00, 0x1f});
        byte[] delta = accessUnit(0x41, new byte[] {0x68, (byte) 0xce});
        byte[] stream = concatenate(new byte[] {8, 7, 6}, key, delta);
        AnnexBAccessUnitParser parser = new AnnexBAccessUnitParser(1024);
        List<VideoAccessUnit> units = new ArrayList<>();

        for (int offset = 0; offset < stream.length; offset += 2) {
            units.addAll(parser.accept(stream, offset, Math.min(2, stream.length - offset)));
        }
        units.addAll(parser.flush());

        assertEquals(2, units.size());
        assertTrue(units.get(0).keyFrame());
        assertFalse(units.get(1).keyFrame());
        assertArrayEquals(key, units.get(0).payload());
        assertArrayEquals(delta, units.get(1).payload());
    }

    @Test
    void suppressesAudWithoutVideoSlice() {
        AnnexBAccessUnitParser parser = new AnnexBAccessUnitParser(1024);
        byte[] parameterOnly = concatenate(startCode(0x09), startCode(0x67));

        assertTrue(parser.accept(concatenate(parameterOnly, accessUnit(0x41, new byte[0]))).isEmpty());
        assertEquals(1, parser.flush().size());
    }

    private static byte[] accessUnit(int sliceHeader, byte[] parameterPayload) {
        return concatenate(
                startCode(0x09),
                concatenate(new byte[] {0, 0, 0, 1, parameterPayload.length == 0 ? 0x67 : parameterPayload[0]},
                        java.util.Arrays.copyOfRange(parameterPayload, Math.min(1, parameterPayload.length),
                                parameterPayload.length)),
                startCode(sliceHeader));
    }

    private static byte[] startCode(int nalHeader) {
        return new byte[] {0, 0, 0, 1, (byte) nalHeader};
    }

    private static byte[] concatenate(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.writeBytes(value);
        }
        return output.toByteArray();
    }
}
