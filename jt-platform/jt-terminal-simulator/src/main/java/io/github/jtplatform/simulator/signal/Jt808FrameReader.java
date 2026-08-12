package io.github.jtplatform.simulator.signal;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class Jt808FrameReader {
    public static final int DEFAULT_MAX_FRAME_BYTES = 128 * 1_024;

    private final InputStream input;
    private final int maxFrameBytes;

    public Jt808FrameReader(InputStream input) {
        this(input, DEFAULT_MAX_FRAME_BYTES);
    }

    public Jt808FrameReader(InputStream input, int maxFrameBytes) {
        this.input = Objects.requireNonNull(input, "input");
        if (maxFrameBytes < 3) {
            throw new IllegalArgumentException("maxFrameBytes must be at least 3");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    public byte[] readFrame() throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(256);
        boolean started = false;
        while (true) {
            int next = input.read();
            if (next < 0) {
                throw new EOFException(started
                        ? "JT/T 808 connection closed before the frame delimiter"
                        : "JT/T 808 connection closed");
            }
            if (!started) {
                if (next == 0x7e) {
                    started = true;
                    frame.write(next);
                }
                continue;
            }
            if (next == 0x7e && frame.size() == 1) {
                continue;
            }
            frame.write(next);
            if (frame.size() > maxFrameBytes) {
                throw new IOException("JT/T 808 frame exceeds " + maxFrameBytes + " bytes");
            }
            if (next == 0x7e) {
                return frame.toByteArray();
            }
        }
    }
}
