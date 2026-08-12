package io.github.jtplatform.simulator.stream;

import io.github.jtplatform.simulator.media.MediaFrame;
import io.github.jtplatform.simulator.media.MediaFrameType;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.yzh.protocol.t1078.codec.Jt1078Frame;
import org.yzh.protocol.t1078.codec.Jt1078PacketBatch;
import org.yzh.protocol.t1078.codec.Jt1078PacketEncoder;
import org.yzh.protocol.t1078.codec.Jt1078WireConstants;

public final class Jt1078TcpWriter implements AutoCloseable {
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final OutputStream output;
    private final AutoCloseable connection;
    private final String mobileNo;
    private final int channel;
    private final int maxPayloadBytes;
    private final Jt1078PacketEncoder encoder = new Jt1078PacketEncoder();

    private int sequence;
    private long previousVideoTimestamp = -1L;
    private long previousIFrameTimestamp = -1L;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Jt1078TcpWriter(
            OutputStream output,
            AutoCloseable connection,
            String mobileNo,
            int channel,
            int maxPayloadBytes,
            int initialSequence) {
        this.output = new BufferedOutputStream(Objects.requireNonNull(output, "output"));
        this.connection = Objects.requireNonNull(connection, "connection");
        this.mobileNo = Objects.requireNonNull(mobileNo, "mobileNo");
        if (!mobileNo.matches("\\d{1,12}")) {
            throw new IllegalArgumentException("mobileNo must contain 1..12 digits");
        }
        if (channel < 1 || channel > 255) {
            throw new IllegalArgumentException("channel must be in range 1..255");
        }
        if (maxPayloadBytes < 1 || maxPayloadBytes > 65_535) {
            throw new IllegalArgumentException("maxPayloadBytes must be in range 1..65535");
        }
        if (initialSequence < 0 || initialSequence > 65_535) {
            throw new IllegalArgumentException("initialSequence must be in range 0..65535");
        }
        this.channel = channel;
        this.maxPayloadBytes = maxPayloadBytes;
        this.sequence = initialSequence;
    }

    public static Jt1078TcpWriter connect(
            MediaTarget target,
            String mobileNo,
            int channel,
            int maxPayloadBytes) throws IOException {
        return connect(target, mobileNo, channel, maxPayloadBytes, DEFAULT_CONNECT_TIMEOUT);
    }

    public static Jt1078TcpWriter connect(
            MediaTarget target,
            String mobileNo,
            int channel,
            int maxPayloadBytes,
            Duration timeout) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("timeout must be in range 1ms..2147483647ms");
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(target.resolve(), target.port()), (int) timeout.toMillis());
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            return new Jt1078TcpWriter(
                    socket.getOutputStream(), socket, mobileNo, channel, maxPayloadBytes, 0);
        } catch (IOException | RuntimeException failure) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Preserve the connection failure.
            }
            throw failure;
        }
    }

    static Jt1078TcpWriter forOutput(
            OutputStream output,
            String mobileNo,
            int channel,
            int maxPayloadBytes,
            int initialSequence) {
        return new Jt1078TcpWriter(
                output, output, mobileNo, channel, maxPayloadBytes, initialSequence);
    }

    public WriteResult write(MediaFrame mediaFrame) throws IOException {
        Objects.requireNonNull(mediaFrame, "mediaFrame");
        ensureOpen();

        int dataType = switch (mediaFrame.type()) {
            case VIDEO_I -> Jt1078WireConstants.VIDEO_I_FRAME;
            case VIDEO_P -> Jt1078WireConstants.VIDEO_P_FRAME;
            case AUDIO -> Jt1078WireConstants.AUDIO_FRAME;
        };
        int payloadType = mediaFrame.type() == MediaFrameType.AUDIO
                ? Jt1078WireConstants.PT_G711A : Jt1078WireConstants.PT_H264;
        int lastIFrameInterval = 0;
        int lastFrameInterval = 0;
        if (mediaFrame.type().video()) {
            lastIFrameInterval = interval(previousIFrameTimestamp, mediaFrame.timestampMillis());
            lastFrameInterval = interval(previousVideoTimestamp, mediaFrame.timestampMillis());
        }

        Jt1078Frame frame = new Jt1078Frame(
                mobileNo,
                channel,
                dataType,
                payloadType,
                mediaFrame.timestampMillis(),
                lastIFrameInterval,
                lastFrameInterval,
                mediaFrame.payload());
        Jt1078PacketBatch batch = encoder.encodeFrame(frame, maxPayloadBytes, sequence);
        List<byte[]> packets = batch.packets();
        int encodedBytes = 0;
        for (byte[] packet : packets) {
            output.write(packet);
            encodedBytes = Math.addExact(encodedBytes, packet.length);
        }
        output.flush();
        sequence = batch.nextSequence();
        if (mediaFrame.type().video()) {
            previousVideoTimestamp = mediaFrame.timestampMillis();
            if (mediaFrame.type().keyFrame()) {
                previousIFrameTimestamp = mediaFrame.timestampMillis();
            }
        }
        return new WriteResult(packets.size(), encodedBytes, sequence);
    }

    public int nextSequence() {
        return sequence;
    }

    private static int interval(long previous, long current) {
        if (previous < 0 || current <= previous) {
            return 0;
        }
        return (int) Math.min(65_535L, current - previous);
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("JT/T 1078 writer is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException failure = null;
        try {
            connection.close();
        } catch (Exception closeFailure) {
            failure = closeFailure instanceof IOException io
                    ? io : new IOException("Unable to close media connection", closeFailure);
        }
        try {
            output.close();
        } catch (IOException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public record WriteResult(int packetCount, int encodedBytes, int nextSequence) {
    }
}
