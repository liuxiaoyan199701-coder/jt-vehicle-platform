package io.github.jtplatform.media.protocol;

public enum FragmentFlag {
    ATOMIC(0),
    FIRST(1),
    LAST(2),
    MIDDLE(3);

    private final int wireValue;

    FragmentFlag(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public static FragmentFlag fromWireValue(int value) {
        return switch (value) {
            case 0 -> ATOMIC;
            case 1 -> FIRST;
            case 2 -> LAST;
            case 3 -> MIDDLE;
            default -> throw new IllegalArgumentException("Unsupported JT/T 1078 fragment flag: " + value);
        };
    }
}
