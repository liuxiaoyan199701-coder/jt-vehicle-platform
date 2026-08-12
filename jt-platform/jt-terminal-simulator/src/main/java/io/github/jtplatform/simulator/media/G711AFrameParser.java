package io.github.jtplatform.simulator.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class G711AFrameParser {
    public static final int SAMPLE_RATE = 8_000;
    public static final int FRAME_BYTES = 160;
    public static final int FRAME_DURATION_MILLIS = 20;

    private final MediaTimeline timeline;
    private final byte[] pending = new byte[FRAME_BYTES];
    private int pendingBytes;
    private long completedFrames;
    private long streamAnchorMillis = -1L;

    public G711AFrameParser(MediaTimeline timeline) {
        this.timeline = Objects.requireNonNull(timeline, "timeline");
    }

    public List<MediaFrame> accept(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return accept(bytes, 0, bytes.length);
    }

    public List<MediaFrame> accept(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (streamAnchorMillis < 0 && length > 0) {
            streamAnchorMillis = timeline.nowMillis();
        }
        List<MediaFrame> frames = new ArrayList<>();
        int cursor = offset;
        int remaining = length;
        while (remaining > 0) {
            int copied = Math.min(FRAME_BYTES - pendingBytes, remaining);
            System.arraycopy(bytes, cursor, pending, pendingBytes, copied);
            cursor += copied;
            remaining -= copied;
            pendingBytes += copied;
            if (pendingBytes == FRAME_BYTES) {
                long samples = Math.multiplyExact(completedFrames, FRAME_BYTES);
                long timestamp = timeline.timestampAfterSamples(streamAnchorMillis, samples, SAMPLE_RATE);
                frames.add(new MediaFrame(MediaFrameType.AUDIO, timestamp,
                        Arrays.copyOf(pending, pending.length)));
                completedFrames++;
                pendingBytes = 0;
            }
        }
        return List.copyOf(frames);
    }

    public int pendingBytes() {
        return pendingBytes;
    }

    public void reset() {
        pendingBytes = 0;
        completedFrames = 0;
        streamAnchorMillis = -1L;
    }
}
