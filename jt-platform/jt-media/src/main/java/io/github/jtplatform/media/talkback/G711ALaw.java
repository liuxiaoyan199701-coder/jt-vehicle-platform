package io.github.jtplatform.media.talkback;

final class G711ALaw {
    private static final int[] SEGMENT_ENDS = {
            0x1f, 0x3f, 0x7f, 0xff, 0x1ff, 0x3ff, 0x7ff, 0xfff
    };

    private G711ALaw() {
    }

    static byte[] mix(byte[][] frames) {
        if (frames.length == 0) {
            return new byte[0];
        }
        if (frames.length == 1) {
            return frames[0].clone();
        }
        int length = 0;
        for (byte[] frame : frames) {
            length = Math.max(length, frame.length);
        }
        byte[] mixed = new byte[length];
        for (int index = 0; index < length; index++) {
            int total = 0;
            int contributors = 0;
            for (byte[] frame : frames) {
                if (index < frame.length) {
                    total += decode(frame[index]);
                    contributors++;
                }
            }
            mixed[index] = encode(total / Math.max(1, contributors));
        }
        return mixed;
    }

    static int decode(byte encoded) {
        int input = (encoded & 0xff) ^ 0x55;
        int magnitude = (input & 0x0f) << 4;
        int segment = (input & 0x70) >>> 4;
        magnitude = switch (segment) {
            case 0 -> magnitude + 8;
            case 1 -> magnitude + 0x108;
            default -> (magnitude + 0x108) << (segment - 1);
        };
        return (input & 0x80) != 0 ? magnitude : -magnitude;
    }

    static byte encode(int pcm) {
        int sample = pcm >> 3;
        int mask;
        if (sample >= 0) {
            mask = 0xd5;
        } else {
            mask = 0x55;
            sample = -sample - 1;
        }

        int segment = segmentFor(sample);
        if (segment >= SEGMENT_ENDS.length) {
            return (byte) (0x7f ^ mask);
        }
        int encoded = segment << 4;
        encoded |= segment < 2
                ? (sample >>> 1) & 0x0f
                : (sample >>> segment) & 0x0f;
        return (byte) (encoded ^ mask);
    }

    private static int segmentFor(int sample) {
        for (int segment = 0; segment < SEGMENT_ENDS.length; segment++) {
            if (sample <= SEGMENT_ENDS[segment]) {
                return segment;
            }
        }
        return SEGMENT_ENDS.length;
    }
}
