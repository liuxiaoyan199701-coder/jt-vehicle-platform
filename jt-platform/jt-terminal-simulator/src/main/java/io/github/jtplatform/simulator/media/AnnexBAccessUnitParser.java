package io.github.jtplatform.simulator.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class AnnexBAccessUnitParser {
    private static final int AUD_NAL_TYPE = 9;
    private static final int NON_IDR_SLICE_NAL_TYPE = 1;
    private static final int IDR_SLICE_NAL_TYPE = 5;

    private final int maxAccessUnitBytes;
    private byte[] buffer = new byte[8192];
    private int size;

    public AnnexBAccessUnitParser(int maxAccessUnitBytes) {
        if (maxAccessUnitBytes < 64) {
            throw new IllegalArgumentException("maxAccessUnitBytes must be at least 64");
        }
        this.maxAccessUnitBytes = maxAccessUnitBytes;
    }

    public List<VideoAccessUnit> accept(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return accept(bytes, 0, bytes.length);
    }

    public List<VideoAccessUnit> accept(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        append(bytes, offset, length);
        return extract(false);
    }

    public List<VideoAccessUnit> flush() {
        List<VideoAccessUnit> completed = extract(true);
        size = 0;
        return completed;
    }

    public void reset() {
        size = 0;
    }

    private List<VideoAccessUnit> extract(boolean flush) {
        List<VideoAccessUnit> completed = new ArrayList<>();
        int firstAud = findAud(0);
        if (firstAud < 0) {
            retainPotentialStartCode();
            return List.of();
        }
        if (firstAud > 0) {
            discardPrefix(firstAud);
        }

        while (true) {
            int nextAud = findAud(startCodeLength(buffer, 0) + 1);
            if (nextAud < 0) {
                break;
            }
            addIfVideo(completed, Arrays.copyOfRange(buffer, 0, nextAud));
            discardPrefix(nextAud);
        }

        if (flush && size > 0) {
            addIfVideo(completed, Arrays.copyOf(buffer, size));
            size = 0;
        } else if (size > maxAccessUnitBytes) {
            size = 0;
            throw new IllegalStateException("H.264 access unit exceeds " + maxAccessUnitBytes + " bytes");
        }
        return List.copyOf(completed);
    }

    private static void addIfVideo(List<VideoAccessUnit> output, byte[] accessUnit) {
        boolean hasSlice = false;
        boolean keyFrame = false;
        int offset = findStartCode(accessUnit, 0, accessUnit.length);
        while (offset >= 0) {
            int headerIndex = offset + startCodeLength(accessUnit, offset);
            if (headerIndex < accessUnit.length) {
                int type = accessUnit[headerIndex] & 0x1f;
                if (type == IDR_SLICE_NAL_TYPE) {
                    keyFrame = true;
                    hasSlice = true;
                } else if (type == NON_IDR_SLICE_NAL_TYPE) {
                    hasSlice = true;
                }
            }
            offset = findStartCode(accessUnit, headerIndex + 1, accessUnit.length);
        }
        if (hasSlice) {
            output.add(new VideoAccessUnit(keyFrame, accessUnit));
        }
    }

    private int findAud(int from) {
        int start = findStartCode(buffer, from, size);
        while (start >= 0) {
            int header = start + startCodeLength(buffer, start);
            if (header >= size) {
                return -1;
            }
            if ((buffer[header] & 0x1f) == AUD_NAL_TYPE) {
                return start;
            }
            start = findStartCode(buffer, header + 1, size);
        }
        return -1;
    }

    private static int findStartCode(byte[] data, int from, int limit) {
        for (int index = Math.max(0, from); index + 2 < limit; index++) {
            if (data[index] == 0 && data[index + 1] == 0
                    && (data[index + 2] == 1
                    || (index + 3 < limit && data[index + 2] == 0 && data[index + 3] == 1))) {
                return index;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int offset) {
        return data[offset + 2] == 1 ? 3 : 4;
    }

    private void append(byte[] bytes, int offset, int length) {
        if ((long) size + length > maxAccessUnitBytes + 8L * 1024L) {
            size = 0;
            throw new IllegalStateException("H.264 parser buffer exceeded its safety limit");
        }
        ensureCapacity(size + length);
        System.arraycopy(bytes, offset, buffer, size, length);
        size += length;
    }

    private void ensureCapacity(int required) {
        if (required <= buffer.length) {
            return;
        }
        int capacity = buffer.length;
        while (capacity < required) {
            capacity = Math.max(capacity + 1, capacity * 2);
        }
        buffer = Arrays.copyOf(buffer, capacity);
    }

    private void discardPrefix(int length) {
        System.arraycopy(buffer, length, buffer, 0, size - length);
        size -= length;
    }

    private void retainPotentialStartCode() {
        if (size <= 4) {
            return;
        }
        System.arraycopy(buffer, size - 4, buffer, 0, 4);
        size = 4;
    }
}
