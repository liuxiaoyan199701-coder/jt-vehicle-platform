package org.yzh.protocol.t1078.codec;

public enum Jt1078FragmentFlag {
    ATOMIC(0),
    FIRST(1),
    LAST(2),
    MIDDLE(3);

    private final int wireValue;

    Jt1078FragmentFlag(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public static Jt1078FragmentFlag fromWireValue(int value) {
        return switch (value) {
            case 0 -> ATOMIC;
            case 1 -> FIRST;
            case 2 -> LAST;
            case 3 -> MIDDLE;
            default -> throw new IllegalArgumentException(
                    "Unsupported JT/T 1078 fragment flag: " + value);
        };
    }
}
