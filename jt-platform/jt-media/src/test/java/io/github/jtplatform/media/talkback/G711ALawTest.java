package io.github.jtplatform.media.talkback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class G711ALawTest {
    @ParameterizedTest
    @MethodSource("decodeVectors")
    void decodesStandardVectors(int encoded, int expectedPcm) {
        assertEquals(expectedPcm, G711ALaw.decode((byte) encoded));
    }

    @ParameterizedTest
    @MethodSource("encodeVectors")
    void encodesStandardVectors(int pcm, int expectedEncoded) {
        assertEquals(expectedEncoded, Byte.toUnsignedInt(G711ALaw.encode(pcm)));
    }

    @Test
    void mixesInThePcmDomainAndHandlesUnequalFrameLengths() {
        assertArrayEquals(bytes(0xc7), G711ALaw.mix(new byte[][] {bytes(0xc7), bytes(0xc7)}));
        assertArrayEquals(bytes(0xd5), G711ALaw.mix(new byte[][] {bytes(0xc7), bytes(0x47)}));
        assertArrayEquals(bytes(0xd5), G711ALaw.mix(new byte[][] {bytes(0xfa), bytes(0x7a)}));
        assertArrayEquals(bytes(0xca), G711ALaw.mix(new byte[][] {bytes(0xfa), bytes(0xd5)}));
        assertArrayEquals(bytes(0xc0), G711ALaw.mix(
                new byte[][] {bytes(0xfa), bytes(0xfa), bytes(0x7a)}));
        assertArrayEquals(bytes(0xd5, 0xc7), G711ALaw.mix(
                new byte[][] {bytes(0xc7, 0xc7), bytes(0x47)}));
    }

    private static Stream<Arguments> decodeVectors() {
        return Stream.of(
                arguments(0xd5, 8),
                arguments(0x55, -8),
                arguments(0xd4, 24),
                arguments(0x54, -24),
                arguments(0xc5, 264),
                arguments(0x45, -264),
                arguments(0xc7, 296),
                arguments(0x47, -296),
                arguments(0xfa, 1008),
                arguments(0x7a, -1008),
                arguments(0xaa, 32256),
                arguments(0x2a, -32256));
    }

    private static Stream<Arguments> encodeVectors() {
        return Stream.of(
                arguments(0, 0xd5),
                arguments(1, 0xd5),
                arguments(-1, 0x55),
                arguments(8, 0xd5),
                arguments(-8, 0x55),
                arguments(300, 0xc7),
                arguments(-300, 0x47),
                arguments(1000, 0xfa),
                arguments(-1000, 0x7a),
                arguments(32767, 0xaa),
                arguments(-32768, 0x2a));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
