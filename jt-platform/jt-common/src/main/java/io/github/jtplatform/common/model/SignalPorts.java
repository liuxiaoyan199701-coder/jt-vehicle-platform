package io.github.jtplatform.common.model;

public record SignalPorts(int management, int tcp, int udp, int command) {
    public static SignalPorts forInstance(int instanceNumber) {
        if (instanceNumber < 1 || instanceNumber > 9) {
            throw new IllegalArgumentException("instanceNumber must be in range 1..9");
        }
        int base = 7100 + instanceNumber * 10;
        return new SignalPorts(base, base + 1, base + 2, base + 3);
    }
}
