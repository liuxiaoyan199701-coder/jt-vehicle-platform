package io.github.jtplatform.simulator.media;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MjpegStreamParser {
    private static final int MARKER_PREFIX = 0xff;
    private static final int START_OF_IMAGE = 0xd8;
    private static final int END_OF_IMAGE = 0xd9;

    private final int maxFrameBytes;
    private ByteArrayOutputStream frame;
    private int previous = -1;

    public MjpegStreamParser(int maxFrameBytes) {
        if (maxFrameBytes < 4) {
            throw new IllegalArgumentException("maxFrameBytes must be at least 4");
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    public List<byte[]> accept(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return accept(bytes, 0, bytes.length);
    }

    public List<byte[]> accept(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        List<byte[]> completed = new ArrayList<>();
        for (int index = offset; index < offset + length; index++) {
            int current = bytes[index] & 0xff;
            if (frame == null) {
                if (previous == MARKER_PREFIX && current == START_OF_IMAGE) {
                    frame = new ByteArrayOutputStream(Math.min(maxFrameBytes, 64 * 1024));
                    frame.write(MARKER_PREFIX);
                    frame.write(START_OF_IMAGE);
                }
                previous = current;
                continue;
            }

            frame.write(current);
            if (frame.size() > maxFrameBytes) {
                reset();
                throw new IllegalStateException("MJPEG frame exceeds " + maxFrameBytes + " bytes");
            }
            if (previous == MARKER_PREFIX && current == END_OF_IMAGE) {
                completed.add(frame.toByteArray());
                frame = null;
                previous = -1;
            } else {
                previous = current;
            }
        }
        return List.copyOf(completed);
    }

    public void reset() {
        frame = null;
        previous = -1;
    }
}
