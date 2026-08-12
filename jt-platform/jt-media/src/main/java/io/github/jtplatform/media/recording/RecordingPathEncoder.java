package io.github.jtplatform.media.recording;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class RecordingPathEncoder {
    private RecordingPathEncoder() {
    }

    static String encode(String value) {
        if (!value.isEmpty() && !value.equals(".") && !value.equals("..")
                && value.chars().allMatch(RecordingPathEncoder::safeCharacter)) {
            return value;
        }
        return "~" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean safeCharacter(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '.' || value == '_' || value == '-';
    }
}
