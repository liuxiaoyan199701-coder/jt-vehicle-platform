package io.github.jtplatform.media.recording;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

final class RecordingPlaybackCursor implements AutoCloseable {
    private static final byte[] DATA_MAGIC = {'J', 'T', 'R', '1'};
    private static final byte[] INDEX_MAGIC = {'J', 'T', 'I', '1'};
    private static final int KEY_FRAME_FLAG = 1;
    private static final int BOOTSTRAP_FLAGS = (1 << 1) | (1 << 2);
    private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StreamKey streamKey;
    private final long endTimestampUs;
    private final List<SegmentSlice> slices;

    private int nextSlice;
    private SegmentReader reader;

    private RecordingPlaybackCursor(
            StreamKey streamKey,
            long endTimestampUs,
            List<SegmentSlice> slices) {
        this.streamKey = streamKey;
        this.endTimestampUs = endTimestampUs;
        this.slices = slices;
    }

    static RecordingPlaybackCursor open(
            RecordingSearchService searchService,
            StreamKey streamKey,
            long targetTimestampUs,
            long endTimestampUs) throws IOException {
        Objects.requireNonNull(searchService, "searchService");
        Objects.requireNonNull(streamKey, "streamKey");

        List<RecordingSegmentReference> candidates = searchService.committedSegments(streamKey).stream()
                .filter(segment -> segment.startTimestampUs() <= endTimestampUs)
                .toList();
        if (candidates.isEmpty()) {
            return new RecordingPlaybackCursor(streamKey, endTimestampUs, List.of());
        }

        int firstSegmentIndex = 0;
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).startTimestampUs() <= targetTimestampUs) {
                firstSegmentIndex = index;
            } else {
                break;
            }
        }
        List<RecordingSegmentReference> available = candidates.subList(
                firstSegmentIndex, candidates.size());

        RecordingSegmentReference first = available.getFirst();
        List<IndexEntry> entries = readIndex(first);
        IndexEntry anchor = selectAnchor(first, entries, targetTimestampUs);
        List<Long> bootstrapOffsets = entries.stream()
                .takeWhile(IndexEntry::bootstrap)
                .filter(entry -> entry.fileOffset() < anchor.fileOffset())
                .map(IndexEntry::fileOffset)
                .toList();

        List<SegmentSlice> slices = new ArrayList<>();
        slices.add(new SegmentSlice(
                first, anchor.fileOffset(), anchor.timestampUs(), bootstrapOffsets));
        for (int index = 1; index < available.size(); index++) {
            slices.add(new SegmentSlice(available.get(index), -1, -1, List.of()));
        }
        return new RecordingPlaybackCursor(streamKey, endTimestampUs, List.copyOf(slices));
    }

    MediaFrame next() throws IOException {
        while (true) {
            if (reader == null) {
                if (nextSlice >= slices.size()) {
                    return null;
                }
                SegmentSlice slice = slices.get(nextSlice++);
                reader = new SegmentReader(
                        slice.segment(),
                        streamKey,
                        slice.startOffset(),
                        slice.bootstrapTimestampUs(),
                        slice.bootstrapOffsets(),
                        endTimestampUs);
            }
            MediaFrame frame = reader.next();
            if (frame != null) {
                return frame;
            }
            reader.close();
            reader = null;
        }
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    private static IndexEntry selectAnchor(
            RecordingSegmentReference segment,
            List<IndexEntry> entries,
            long targetTimestampUs) throws IOException {
        List<IndexEntry> candidates = segment.keyFrameCount() > 0
                ? entries.stream().filter(IndexEntry::keyFrame).toList()
                : entries.stream().filter(entry -> !entry.bootstrap()).toList();
        if (candidates.isEmpty()) {
            throw new IOException("recording segment has no playable frame index");
        }

        IndexEntry selected = null;
        if (segment.startTimestampUs() <= targetTimestampUs) {
            for (IndexEntry candidate : candidates) {
                if (candidate.timestampUs() <= targetTimestampUs
                        && (selected == null || candidate.timestampUs() >= selected.timestampUs())) {
                    selected = candidate;
                }
            }
        }
        return selected == null ? candidates.getFirst() : selected;
    }

    private static List<IndexEntry> readIndex(RecordingSegmentReference segment) throws IOException {
        try (FileChannel channel = FileChannel.open(segment.indexPath(), StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < RecordingSegmentWriter.INDEX_HEADER_LENGTH
                    || (size - RecordingSegmentWriter.INDEX_HEADER_LENGTH)
                    % RecordingSegmentWriter.INDEX_ENTRY_LENGTH != 0) {
                throw new IOException("recording index length is invalid");
            }
            ByteBuffer header = read(channel, 0, RecordingSegmentWriter.INDEX_HEADER_LENGTH);
            expectMagic(header, INDEX_MAGIC, "index");
            int version = Short.toUnsignedInt(header.getShort());
            int entryLength = Short.toUnsignedInt(header.getShort());
            long startTimestampUs = header.getLong();
            if (version != RecordingSegmentWriter.VERSION
                    || entryLength != RecordingSegmentWriter.INDEX_ENTRY_LENGTH
                    || startTimestampUs != segment.startTimestampUs()) {
                throw new IOException("recording index header does not match segment");
            }

            List<IndexEntry> entries = new ArrayList<>();
            for (long position = RecordingSegmentWriter.INDEX_HEADER_LENGTH;
                    position < size;
                    position += RecordingSegmentWriter.INDEX_ENTRY_LENGTH) {
                ByteBuffer entry = read(channel, position, RecordingSegmentWriter.INDEX_ENTRY_LENGTH);
                entries.add(new IndexEntry(
                        entry.getLong(),
                        entry.getLong(),
                        entry.getInt(),
                        entry.getInt()));
            }
            return List.copyOf(entries);
        }
    }

    private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position + buffer.position());
            if (count < 0) {
                throw new IOException("unexpected end of recording file");
            }
        }
        buffer.flip();
        return buffer;
    }

    private static void expectMagic(ByteBuffer buffer, byte[] expected, String kind) throws IOException {
        for (byte value : expected) {
            if (buffer.get() != value) {
                throw new IOException(kind + " magic does not match");
            }
        }
    }

    private record SegmentSlice(
            RecordingSegmentReference segment,
            long startOffset,
            long bootstrapTimestampUs,
            List<Long> bootstrapOffsets) {
    }

    private record IndexEntry(
            long timestampUs,
            long fileOffset,
            int totalRecordLength,
            int flags) {

        private boolean keyFrame() {
            return (flags & KEY_FRAME_FLAG) != 0;
        }

        private boolean bootstrap() {
            return (flags & BOOTSTRAP_FLAGS) != 0;
        }
    }

    private static final class SegmentReader implements AutoCloseable {
        private final RecordingSegmentReference segment;
        private final StreamKey streamKey;
        private final long endTimestampUs;
        private final FileChannel channel;
        private final long size;
        private final ArrayDeque<Long> bootstrapOffsets;
        private final long bootstrapTimestampUs;

        private long position;

        private SegmentReader(
                RecordingSegmentReference segment,
                StreamKey streamKey,
                long startOffset,
                long bootstrapTimestampUs,
                List<Long> bootstrapOffsets,
                long endTimestampUs) throws IOException {
            this.segment = segment;
            this.streamKey = streamKey;
            this.endTimestampUs = endTimestampUs;
            this.channel = FileChannel.open(segment.dataPath(), StandardOpenOption.READ);
            this.size = channel.size();
            long dataStart;
            try {
                dataStart = readAndVerifyHeader();
            } catch (IOException | RuntimeException failure) {
                try {
                    close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
            this.position = startOffset < 0 ? dataStart : startOffset;
            if (position < dataStart || position >= size) {
                close();
                throw new IOException("recording start offset is outside data records");
            }
            this.bootstrapOffsets = new ArrayDeque<>();
            for (long offset : bootstrapOffsets) {
                this.bootstrapOffsets.addLast(offset);
            }
            this.bootstrapTimestampUs = bootstrapTimestampUs;
        }

        private MediaFrame next() throws IOException {
            while (true) {
                if (!bootstrapOffsets.isEmpty()) {
                    StoredFrame stored = readFrame(bootstrapOffsets.removeFirst());
                    MediaFrame frame = stored.frame();
                    if (bootstrapTimestampUs >= 0 && frame.timestamp() != bootstrapTimestampUs) {
                        frame = new MediaFrame(
                                frame.streamKey(),
                                frame.type(),
                                frame.codec(),
                                bootstrapTimestampUs,
                                frame.payload());
                    }
                    if (frame.timestamp() <= endTimestampUs) {
                        return frame;
                    }
                    continue;
                }
                if (position >= size) {
                    return null;
                }
                StoredFrame stored = readFrame(position);
                position += stored.totalLength();
                if (stored.frame().timestamp() <= endTimestampUs) {
                    return stored.frame();
                }
            }
        }

        private long readAndVerifyHeader() throws IOException {
            if (size < RecordingSegmentWriter.DATA_FIXED_HEADER_LENGTH) {
                throw new IOException("recording data header is truncated");
            }
            ByteBuffer header = read(channel, 0, RecordingSegmentWriter.DATA_FIXED_HEADER_LENGTH);
            expectMagic(header, DATA_MAGIC, "data");
            int version = Short.toUnsignedInt(header.getShort());
            int fixedHeaderLength = Short.toUnsignedInt(header.getShort());
            long startTimestampUs = header.getLong();
            int descriptorLength = header.getInt();
            header.getInt();
            if (version != RecordingSegmentWriter.VERSION
                    || fixedHeaderLength != RecordingSegmentWriter.DATA_FIXED_HEADER_LENGTH
                    || startTimestampUs != segment.startTimestampUs()) {
                throw new IOException("recording data header does not match segment");
            }
            if (descriptorLength < 0
                    || descriptorLength > MAX_DESCRIPTOR_BYTES
                    || fixedHeaderLength + (long) descriptorLength > size) {
                throw new IOException("recording descriptor length is invalid");
            }
            byte[] descriptorBytes = read(channel, fixedHeaderLength, descriptorLength).array();
            @SuppressWarnings("unchecked")
            Map<String, Object> descriptor = JSON.readValue(descriptorBytes, Map.class);
            verifyDescriptor(descriptor);
            return fixedHeaderLength + (long) descriptorLength;
        }

        private void verifyDescriptor(Map<String, Object> descriptor) throws IOException {
            Object channelValue = descriptor.get("channel");
            if (!streamKey.deviceId().equals(descriptor.get("deviceId"))
                    || !(channelValue instanceof Number number)
                    || number.intValue() != streamKey.channel()
                    || !streamKey.streamKind().wireValue().equals(descriptor.get("streamKind"))) {
                throw new IOException("recording descriptor does not match requested stream");
            }
        }

        private StoredFrame readFrame(long offset) throws IOException {
            if (offset < 0 || size - offset < RecordingSegmentWriter.RECORD_HEADER_LENGTH) {
                throw new IOException("recording frame header is truncated");
            }
            ByteBuffer header = read(channel, offset, RecordingSegmentWriter.RECORD_HEADER_LENGTH);
            int recordLength = header.getInt();
            int frameTypeValue = Byte.toUnsignedInt(header.get());
            int codecValue = Byte.toUnsignedInt(header.get());
            header.getShort();
            long timestampUs = header.getLong();
            int payloadLength = header.getInt();
            if (recordLength < 0 || recordLength != payloadLength) {
                throw new IOException("recording frame length is invalid");
            }
            long totalLength = RecordingSegmentWriter.RECORD_HEADER_LENGTH + (long) payloadLength;
            if (totalLength > Integer.MAX_VALUE || totalLength > size - offset) {
                throw new IOException("recording frame payload is truncated");
            }
            byte[] payload = read(
                    channel,
                    offset + RecordingSegmentWriter.RECORD_HEADER_LENGTH,
                    payloadLength).array();
            MediaFrameType type = frameType(frameTypeValue);
            MediaCodec codec = codec(codecValue, type);
            return new StoredFrame(
                    new MediaFrame(streamKey, type, codec, timestampUs, payload),
                    (int) totalLength);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }

        private static MediaFrameType frameType(int value) throws IOException {
            return switch (value) {
                case 0x00 -> MediaFrameType.VIDEO_KEY;
                case 0x01 -> MediaFrameType.VIDEO_DELTA;
                case 0x02 -> MediaFrameType.VIDEO_B;
                case 0x03 -> MediaFrameType.AUDIO;
                case 0x04 -> MediaFrameType.TRANSPARENT;
                case 0xf0 -> MediaFrameType.SPS;
                case 0xf1 -> MediaFrameType.PPS;
                case 0xf2 -> MediaFrameType.AUDIO_CONFIG;
                case 0xf3 -> MediaFrameType.VPS;
                default -> throw new IOException("unsupported recording frame type: " + value);
            };
        }

        private static MediaCodec codec(int value, MediaFrameType type) throws IOException {
            return switch (value) {
                case 0 -> type == MediaFrameType.TRANSPARENT
                        ? MediaCodec.TRANSPARENT
                        : MediaCodec.UNKNOWN;
                case 1 -> MediaCodec.H264;
                case 2 -> MediaCodec.H265;
                case 3 -> MediaCodec.AAC;
                case 4 -> MediaCodec.G711A;
                default -> throw new IOException("unsupported recording codec: " + value);
            };
        }
    }

    private record StoredFrame(MediaFrame frame, int totalLength) {
    }
}
