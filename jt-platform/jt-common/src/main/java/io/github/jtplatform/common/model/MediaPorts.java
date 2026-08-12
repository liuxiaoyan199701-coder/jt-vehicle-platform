package io.github.jtplatform.common.model;

public record MediaPorts(
        int management,
        int main,
        int sub,
        int playback,
        int talkback,
        int websocket,
        int httpFlv) {

    public static MediaPorts forInstance(int instanceNumber) {
        validateInstanceNumber(instanceNumber);
        int base = 7800 + instanceNumber * 10;
        return new MediaPorts(base, base + 1, base + 2, base + 3, base + 4, base + 5, base + 6);
    }

    public int ingestPort(StreamKind kind) {
        return switch (kind) {
            case MAIN -> main;
            case SUB -> sub;
            case PLAYBACK -> playback;
            case TALKBACK -> talkback;
        };
    }

    private static void validateInstanceNumber(int instanceNumber) {
        if (instanceNumber < 1 || instanceNumber > 9) {
            throw new IllegalArgumentException("instanceNumber must be in range 1..9");
        }
    }
}
