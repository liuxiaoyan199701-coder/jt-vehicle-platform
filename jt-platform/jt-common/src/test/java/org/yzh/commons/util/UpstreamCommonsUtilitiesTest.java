package org.yzh.commons.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamCommonsUtilitiesTest {

    @Test
    void stringHelpersDoNotRequireSpring() {
        assertTrue(StrUtils.isNum("0123456789"));
        assertFalse(StrUtils.isNum(null));
        assertFalse(StrUtils.isNum(""));
        assertFalse(StrUtils.isNum("  "));
        assertFalse(StrUtils.isNum("12a"));

        byte[] bytes = {(byte) 0x00, (byte) 0x7f, (byte) 0x80, (byte) 0xff};
        assertEquals("007f80ff", StrUtils.bytes2Hex(bytes));
        assertArrayEquals(bytes, StrUtils.hex2Bytes("007f80ff"));
    }

    @Test
    void aesCtrRoundTripRemainsCompatible() {
        byte[] plaintext = "jt-platform".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = EncryptUtils.encrypt(
                "0123456789abcdef",
                "abcdef0123456789",
                plaintext
        );

        assertArrayEquals(plaintext, EncryptUtils.decrypt(
                "0123456789abcdef",
                "abcdef0123456789",
                encrypted
        ));
    }

    @Test
    void coordinatesOutsideChinaRemainUnchanged() {
        double[] result = CoordTransform.wgs84togcj02(-74.0060, 40.7128);

        assertArrayEquals(new double[]{-74.0060, 40.7128}, result);
    }
}
