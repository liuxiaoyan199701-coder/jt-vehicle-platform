package io.github.jtplatform.media.recording;

import io.github.jtplatform.media.frame.MediaFrameType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public final class RecordingSegmentInspector {
    private static final byte[] DATA_MAGIC = {'J', 'T', 'R', '1'};
    private static final byte[] INDEX_MAGIC = {'J', 'T', 'I', '1'};
    private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordingSegmentInspector() {
    }

    public static RecordingSegmentInspection inspect(Path segmentPath) {
        Objects.requireNonNull(segmentPath, "segmentPath");
        SegmentFiles files = SegmentFiles.from(segmentPath);
        if (!Files.isRegularFile(files.marker())) {
            return RecordingSegmentInspection.incomplete("commit marker is missing");
        }
        if (!Files.isRegularFile(files.data()) || !Files.isRegularFile(files.index())) {
            return RecordingSegmentInspection.corrupt("committed segment is missing data or index file");
        }
        try {
            DataSummary data = inspectData(files.data());
            inspectIndex(files.index(), data);
            Marker marker = readMarker(files.marker());
            verifyMarker(marker, data, Files.size(files.data()), Files.size(files.index()));
            return RecordingSegmentInspection.committed(
                    data.startTimestampUs(), data.endTimestampUs(), data.frameCount(), data.keyFrameCount());
        } catch (IOException | RuntimeException failure) {
            return RecordingSegmentInspection.corrupt(failure.getMessage());
        }
    }

    private static DataSummary inspectData(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < RecordingSegmentWriter.DATA_FIXED_HEADER_LENGTH) {
                throw new IOException("data header is truncated");
            }
            ByteBuffer header = read(channel, 0, RecordingSegmentWriter.DATA_FIXED_HEADER_LENGTH);
            expectMagic(header, DATA_MAGIC, "data");
            int version = Short.toUnsignedInt(header.getShort());
            int fixedHeaderLength = Short.toUnsignedInt(header.getShort());
            long startTimestampUs = header.getLong();
            int descriptorLength = header.getInt();
            header.getInt();
            if (version != RecordingSegmentWriter.VERSION
                    || fixedHeaderLength != RecordingSegmentWriter.DATA_FIXED_HEADER_LENGTH) {
                throw new IOException("unsupported data header version or length");
            }
            if (descriptorLength < 0 || descriptorLength > MAX_DESCRIPTOR_BYTES
                    || fixedHeaderLength + (long) descriptorLength > size) {
                throw new IOException("invalid recording descriptor length");
            }
            byte[] descriptorBytes = read(channel, fixedHeaderLength, descriptorLength).array();
            @SuppressWarnings("unchecked")
            Map<String, Object> descriptor = JSON.readValue(descriptorBytes, Map.class);

            long position = fixedHeaderLength + (long) descriptorLength;
            long endTimestampUs = startTimestampUs;
            long frameCount = 0;
            long keyFrameCount = 0;
            Map<Long, Integer> records = new LinkedHashMap<>();
            List<Integer> frameTypes = new ArrayList<>();
            while (position < size) {
                if (size - position < RecordingSegmentWriter.RECORD_HEADER_LENGTH) {
                    throw new IOException("record header is truncated");
                }
                ByteBuffer record = read(channel, position, RecordingSegmentWriter.RECORD_HEADER_LENGTH);
                int recordLength = record.getInt();
                int frameType = Byte.toUnsignedInt(record.get());
                int codec = Byte.toUnsignedInt(record.get());
                int flags = Short.toUnsignedInt(record.getShort());
                long timestampUs = record.getLong();
                int payloadLength = record.getInt();
                if (recordLength < 0 || recordLength != payloadLength || codec > 4) {
                    throw new IOException("invalid recording record length or codec");
                }
                long totalLength = RecordingSegmentWriter.RECORD_HEADER_LENGTH + (long) payloadLength;
                if (totalLength > Integer.MAX_VALUE || position + totalLength > size) {
                    throw new IOException("record payload is truncated");
                }
                if (frameType == MediaFrameType.VIDEO_KEY.wireValue() && (flags & 1) == 0) {
                    throw new IOException("key frame index flag is missing");
                }
                records.put(position, (int) totalLength);
                frameTypes.add(frameType);
                endTimestampUs = Math.max(endTimestampUs, timestampUs);
                frameCount++;
                if (frameType == MediaFrameType.VIDEO_KEY.wireValue()) {
                    keyFrameCount++;
                }
                position += totalLength;
            }
            if (frameCount == 0) {
                throw new IOException("recording segment contains no frames");
            }
            verifyBootstrap(descriptor, frameTypes);
            return new DataSummary(
                    startTimestampUs, endTimestampUs, frameCount, keyFrameCount, Map.copyOf(records));
        }
    }

    private static void inspectIndex(Path path, DataSummary data) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < RecordingSegmentWriter.INDEX_HEADER_LENGTH
                    || (size - RecordingSegmentWriter.INDEX_HEADER_LENGTH)
                    % RecordingSegmentWriter.INDEX_ENTRY_LENGTH != 0) {
                throw new IOException("index file length is invalid");
            }
            ByteBuffer header = read(channel, 0, RecordingSegmentWriter.INDEX_HEADER_LENGTH);
            expectMagic(header, INDEX_MAGIC, "index");
            int version = Short.toUnsignedInt(header.getShort());
            int entryLength = Short.toUnsignedInt(header.getShort());
            long startTimestampUs = header.getLong();
            if (version != RecordingSegmentWriter.VERSION
                    || entryLength != RecordingSegmentWriter.INDEX_ENTRY_LENGTH
                    || startTimestampUs != data.startTimestampUs()) {
                throw new IOException("index header does not match data file");
            }

            long position = RecordingSegmentWriter.INDEX_HEADER_LENGTH;
            while (position < size) {
                ByteBuffer entry = read(channel, position, RecordingSegmentWriter.INDEX_ENTRY_LENGTH);
                entry.getLong();
                long fileOffset = entry.getLong();
                int totalRecordLength = entry.getInt();
                entry.getInt();
                Integer actualLength = data.records().get(fileOffset);
                if (actualLength == null || actualLength != totalRecordLength) {
                    throw new IOException("index entry points outside a complete record");
                }
                position += RecordingSegmentWriter.INDEX_ENTRY_LENGTH;
            }
        }
    }

    private static Marker readMarker(Path markerPath) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> marker = JSON.readValue(Files.readAllBytes(markerPath), Map.class);
        return new Marker(
                number(marker, "version"),
                number(marker, "startTimestampUs"),
                number(marker, "endTimestampUs"),
                number(marker, "frameCount"),
                number(marker, "keyFrameCount"),
                number(marker, "dataBytes"),
                number(marker, "indexBytes"));
    }

    private static void verifyMarker(Marker marker, DataSummary data, long dataBytes, long indexBytes)
            throws IOException {
        if (marker.version() != RecordingSegmentWriter.VERSION
                || marker.startTimestampUs() != data.startTimestampUs()
                || marker.endTimestampUs() != data.endTimestampUs()
                || marker.frameCount() != data.frameCount()
                || marker.keyFrameCount() != data.keyFrameCount()
                || marker.dataBytes() != dataBytes
                || marker.indexBytes() != indexBytes) {
            throw new IOException("commit marker does not match segment contents");
        }
    }

    private static void verifyBootstrap(Map<String, Object> descriptor, List<Integer> frameTypes)
            throws IOException {
        String videoCodec = Objects.toString(descriptor.get("videoCodec"), "UNKNOWN");
        if (videoCodec.equals("H264")) {
            expectLeading(frameTypes, MediaFrameType.SPS, MediaFrameType.PPS);
            expectFirstVideoKey(frameTypes);
        } else if (videoCodec.equals("H265")) {
            expectLeading(frameTypes, MediaFrameType.VPS, MediaFrameType.SPS, MediaFrameType.PPS);
            expectFirstVideoKey(frameTypes);
        } else if (Objects.toString(descriptor.get("audioCodec"), "UNKNOWN").equals("AAC")
                && frameTypes.getFirst() != MediaFrameType.AUDIO_CONFIG.wireValue()) {
            throw new IOException("AAC segment does not start with audio configuration");
        }
    }

    private static void expectLeading(List<Integer> frameTypes, MediaFrameType... expected) throws IOException {
        if (frameTypes.size() < expected.length) {
            throw new IOException("video parameter-set preamble is truncated");
        }
        for (int index = 0; index < expected.length; index++) {
            if (frameTypes.get(index) != expected[index].wireValue()) {
                throw new IOException("video parameter-set preamble is invalid");
            }
        }
    }

    private static void expectFirstVideoKey(List<Integer> frameTypes) throws IOException {
        for (int frameType : frameTypes) {
            if (frameType == MediaFrameType.VIDEO_KEY.wireValue()
                    || frameType == MediaFrameType.VIDEO_DELTA.wireValue()
                    || frameType == MediaFrameType.VIDEO_B.wireValue()) {
                if (frameType != MediaFrameType.VIDEO_KEY.wireValue()) {
                    throw new IOException("first video frame is not a key frame");
                }
                return;
            }
        }
        throw new IOException("video segment contains no video frame");
    }

    private static long number(Map<String, Object> values, String key) throws IOException {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IOException("commit marker field is missing: " + key);
    }

    private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
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

    private record DataSummary(
            long startTimestampUs,
            long endTimestampUs,
            long frameCount,
            long keyFrameCount,
            Map<Long, Integer> records) {
    }

    private record Marker(
            long version,
            long startTimestampUs,
            long endTimestampUs,
            long frameCount,
            long keyFrameCount,
            long dataBytes,
            long indexBytes) {
    }

    private record SegmentFiles(Path data, Path index, Path marker) {
        private static SegmentFiles from(Path supplied) {
            Path absolute = supplied.toAbsolutePath().normalize();
            String filename = absolute.getFileName().toString();
            String baseName = stripSuffix(filename);
            Path base = absolute.resolveSibling(baseName);
            return new SegmentFiles(
                    Path.of(base + ".jtr"),
                    Path.of(base + ".jti"),
                    Path.of(base + ".ok"));
        }

        private static String stripSuffix(String value) {
            for (String suffix : List.of(
                    ".jtr.part", ".jti.part", ".ok.part", ".jtr", ".jti", ".ok")) {
                if (value.endsWith(suffix)) {
                    return value.substring(0, value.length() - suffix.length());
                }
            }
            return value;
        }
    }
}
