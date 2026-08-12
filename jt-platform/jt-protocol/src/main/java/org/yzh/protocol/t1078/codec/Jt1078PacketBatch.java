package org.yzh.protocol.t1078.codec;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record Jt1078PacketBatch(List<byte[]> packets, int nextSequence) {
    public Jt1078PacketBatch {
        Objects.requireNonNull(packets, "packets");
        packets = packets.stream()
                .map(packet -> Arrays.copyOf(
                        Objects.requireNonNull(packet, "packet"), packet.length))
                .toList();
        if (packets.isEmpty()) {
            throw new IllegalArgumentException("packets must not be empty");
        }
        Jt1078PacketHeader.validateSequence(nextSequence);
    }

    @Override
    public List<byte[]> packets() {
        return packets.stream().map(packet -> Arrays.copyOf(packet, packet.length)).toList();
    }
}
