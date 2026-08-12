package io.github.jtplatform.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum StreamKind {
    MAIN(1, 0, 0),
    SUB(2, 0, 1),
    PLAYBACK(3, 0, 0),
    TALKBACK(4, 2, 0);

    private final int mediaPortOffset;
    private final int dataType;
    private final int streamType;

    StreamKind(int mediaPortOffset, int dataType, int streamType) {
        this.mediaPortOffset = mediaPortOffset;
        this.dataType = dataType;
        this.streamType = streamType;
    }

    public int mediaPortOffset() {
        return mediaPortOffset;
    }

    public int dataType() {
        return dataType;
    }

    public int streamType() {
        return streamType;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static StreamKind fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("streamKind is required");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "main", "main-stream" -> MAIN;
            case "sub", "sub-stream" -> SUB;
            case "playback" -> PLAYBACK;
            case "talkback" -> TALKBACK;
            default -> throw new IllegalArgumentException("Unsupported streamKind: " + value);
        };
    }

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
