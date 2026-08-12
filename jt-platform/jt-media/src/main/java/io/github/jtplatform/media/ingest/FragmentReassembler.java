package io.github.jtplatform.media.ingest;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.media.protocol.FragmentFlag;
import io.github.jtplatform.media.protocol.Jt1078Header;
import io.github.jtplatform.media.protocol.RtpPacket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FragmentReassembler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FragmentReassembler.class);

    private final Duration timeout;
    private final int maxFrameBytes;
    private final Clock clock;
    private final Map<FrameIdentity, Accumulator> incomplete = new HashMap<>();

    public FragmentReassembler(Duration timeout, int maxFrameBytes, Clock clock) {
        this.timeout = requirePositive(timeout, "timeout");
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        this.maxFrameBytes = maxFrameBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Optional<ReassembledPacket> accept(RtpPacket packet) {
        Objects.requireNonNull(packet, "packet");
        Instant now = clock.instant();
        evictExpired(now);

        Jt1078Header header = packet.header();
        if (header.fragmentFlag() == FragmentFlag.ATOMIC) {
            return Optional.of(new ReassembledPacket(packet.streamKey(), header, packet.payload()));
        }

        FrameIdentity identity = FrameIdentity.from(packet);
        return switch (header.fragmentFlag()) {
            case FIRST -> acceptFirst(identity, packet, now);
            case MIDDLE -> acceptContinuation(identity, packet, now, false);
            case LAST -> acceptContinuation(identity, packet, now, true);
            case ATOMIC -> throw new IllegalStateException("Atomic packet handled above");
        };
    }

    public synchronized int evictExpired() {
        return evictExpired(clock.instant());
    }

    public synchronized int incompleteCount() {
        return incomplete.size();
    }

    private Optional<ReassembledPacket> acceptFirst(FrameIdentity identity, RtpPacket packet, Instant now) {
        byte[] payload = packet.payload();
        if (payload.length > maxFrameBytes) {
            LOGGER.warn("Dropping oversized first fragment for {}: {} bytes", identity.streamKey(), payload.length);
            return Optional.empty();
        }
        Accumulator replaced = incomplete.put(identity, new Accumulator(packet.header(), payload, now));
        if (replaced != null) {
            LOGGER.warn("Replacing incomplete JT/T 1078 frame for {} after a new first fragment",
                    identity.streamKey());
        }
        return Optional.empty();
    }

    private Optional<ReassembledPacket> acceptContinuation(
            FrameIdentity identity, RtpPacket packet, Instant now, boolean last) {
        Accumulator accumulator = incomplete.get(identity);
        if (accumulator == null) {
            LOGGER.warn("Dropping {} fragment without a first fragment for {}",
                    last ? "last" : "middle", identity.streamKey());
            return Optional.empty();
        }

        byte[] fragment = packet.payload();
        if (!accumulator.append(fragment, maxFrameBytes, now)) {
            incomplete.remove(identity);
            LOGGER.warn("Dropping oversized reassembled JT/T 1078 frame for {}", identity.streamKey());
            return Optional.empty();
        }
        if (!last) {
            return Optional.empty();
        }

        incomplete.remove(identity);
        return Optional.of(new ReassembledPacket(identity.streamKey(), accumulator.firstHeader(),
                accumulator.payload()));
    }

    private int evictExpired(Instant now) {
        int removed = 0;
        Iterator<Map.Entry<FrameIdentity, Accumulator>> iterator = incomplete.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<FrameIdentity, Accumulator> entry = iterator.next();
            if (!entry.getValue().lastUpdated().plus(timeout).isAfter(now)) {
                iterator.remove();
                removed++;
                LOGGER.warn("Discarded incomplete JT/T 1078 frame after {} timeout for {}",
                        timeout, entry.getKey().streamKey());
            }
        }
        return removed;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private record FrameIdentity(
            StreamKey streamKey,
            long timestamp,
            int dataType,
            int payloadType) {
        private static FrameIdentity from(RtpPacket packet) {
            Jt1078Header header = packet.header();
            return new FrameIdentity(packet.streamKey(), header.timestamp(),
                    header.dataType(), header.payloadType());
        }
    }

    private static final class Accumulator {
        private final Jt1078Header firstHeader;
        private byte[] data;
        private int size;
        private Instant lastUpdated;

        private Accumulator(Jt1078Header firstHeader, byte[] first, Instant lastUpdated) {
            this.firstHeader = firstHeader;
            this.data = new byte[Math.max(first.length, 256)];
            System.arraycopy(first, 0, data, 0, first.length);
            this.size = first.length;
            this.lastUpdated = lastUpdated;
        }

        private boolean append(byte[] fragment, int maximum, Instant now) {
            long required = (long) size + fragment.length;
            if (required > maximum) {
                return false;
            }
            if (required > data.length) {
                int grown = Math.max((int) required, Math.min(maximum, data.length * 2));
                byte[] replacement = new byte[grown];
                System.arraycopy(data, 0, replacement, 0, size);
                data = replacement;
            }
            System.arraycopy(fragment, 0, data, size, fragment.length);
            size += fragment.length;
            lastUpdated = now;
            return true;
        }

        private Jt1078Header firstHeader() {
            return firstHeader;
        }

        private byte[] payload() {
            return java.util.Arrays.copyOf(data, size);
        }

        private Instant lastUpdated() {
            return lastUpdated;
        }
    }
}
