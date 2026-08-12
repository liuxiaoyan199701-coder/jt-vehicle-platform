package io.github.jtplatform.media.recording;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

final class RecordingSegmentWriter {
    static final int DATA_FIXED_HEADER_LENGTH = 24;
    static final int RECORD_HEADER_LENGTH = 20;
    static final int INDEX_HEADER_LENGTH = 16;
    static final int INDEX_ENTRY_LENGTH = 24;
    static final int VERSION = 1;

    private static final byte[] DATA_MAGIC = {'J', 'T', 'R', '1'};
    private static final byte[] INDEX_MAGIC = {'J', 'T', 'I', '1'};
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dataPart;
    private final Path indexPart;
    private final Path dataFile;
    private final Path indexFile;
    private final Path markerPart;
    private final Path markerFile;
    private final StreamKey streamKey;
    private final long startTimestampUs;
    private final MediaCodec videoCodec;
    private final MediaCodec audioCodec;
    private final FileChannel data;
    private final FileChannel index;

    private State state = State.OPEN;
    private long endTimestampUs;
    private long frameCount;
    private long keyFrameCount;

    RecordingSegmentWriter(
            Path root,
            StreamKey streamKey,
            long startTimestampUs,
            MediaCodec videoCodec,
            MediaCodec audioCodec) throws IOException {
        this.startTimestampUs = startTimestampUs;
        this.endTimestampUs = startTimestampUs;
        this.streamKey = streamKey;
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;

        Path directory = segmentDirectory(root, streamKey, startTimestampUs);
        Files.createDirectories(directory);
        String segmentId = startTimestampUs + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path base = directory.resolve(segmentId);
        this.dataPart = Path.of(base + ".jtr.part");
        this.indexPart = Path.of(base + ".jti.part");
        this.dataFile = Path.of(base + ".jtr");
        this.indexFile = Path.of(base + ".jti");
        this.markerPart = Path.of(base + ".ok.part");
        this.markerFile = Path.of(base + ".ok");

        FileChannel openedData = null;
        FileChannel openedIndex = null;
        try {
            openedData = FileChannel.open(dataPart, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            openedIndex = FileChannel.open(indexPart, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            this.data = openedData;
            this.index = openedIndex;
            writeHeaders(streamKey);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(openedIndex);
            closeQuietly(openedData);
            throw failure;
        }
    }

    long startTimestampUs() {
        return startTimestampUs;
    }

    MediaCodec videoCodec() {
        return videoCodec;
    }

    MediaCodec audioCodec() {
        return audioCodec;
    }

    boolean hasVideo() {
        return videoCodec == MediaCodec.H264 || videoCodec == MediaCodec.H265;
    }

    void append(MediaFrame frame) throws IOException {
        ensureOpen();
        byte[] payload = frame.payload();
        int flags = flags(frame.type());
        long offset = data.position();

        ByteBuffer recordHeader = ByteBuffer.allocate(RECORD_HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN);
        recordHeader.putInt(payload.length);
        recordHeader.put((byte) frame.type().wireValue());
        recordHeader.put((byte) codecId(frame.codec()));
        recordHeader.putShort((short) flags);
        recordHeader.putLong(frame.timestamp());
        recordHeader.putInt(payload.length);
        recordHeader.flip();
        writeFully(data, recordHeader);
        writeFully(data, ByteBuffer.wrap(payload));

        ByteBuffer indexEntry = ByteBuffer.allocate(INDEX_ENTRY_LENGTH).order(ByteOrder.BIG_ENDIAN);
        indexEntry.putLong(frame.timestamp());
        indexEntry.putLong(offset);
        indexEntry.putInt(RECORD_HEADER_LENGTH + payload.length);
        indexEntry.putInt(flags);
        indexEntry.flip();
        writeFully(index, indexEntry);

        endTimestampUs = Math.max(endTimestampUs, frame.timestamp());
        frameCount++;
        if (frame.type() == MediaFrameType.VIDEO_KEY) {
            keyFrameCount++;
        }
    }

    RecordingSegmentMetadata commit() throws IOException {
        ensureOpen();
        if (frameCount == 0) {
            throw new IOException("Cannot commit an empty recording segment");
        }
        long dataBytes;
        long indexBytes;
        try {
            data.force(true);
            index.force(true);
            dataBytes = data.size();
            indexBytes = index.size();
        } finally {
            closeQuietly(index);
            closeQuietly(data);
        }

        try {
            moveAtomically(dataPart, dataFile);
            moveAtomically(indexPart, indexFile);
            writeMarker(dataBytes, indexBytes);
            state = State.COMMITTED;
            return new RecordingSegmentMetadata(
                    streamKey,
                    startTimestampUs,
                    endTimestampUs,
                    frameCount,
                    keyFrameCount,
                    dataBytes,
                    indexBytes,
                    dataFile,
                    indexFile,
                    markerFile);
        } catch (IOException | RuntimeException failure) {
            state = State.ABORTED;
            throw failure;
        }
    }

    void abort() {
        if (state != State.OPEN) {
            return;
        }
        state = State.ABORTED;
        closeQuietly(index);
        closeQuietly(data);
    }

    private void writeHeaders(StreamKey streamKey) throws IOException {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("deviceId", streamKey.deviceId());
        descriptor.put("channel", streamKey.channel());
        descriptor.put("streamKind", streamKey.streamKind().wireValue());
        descriptor.put("videoCodec", videoCodec.name());
        descriptor.put("audioCodec", audioCodec.name());
        byte[] descriptorBytes = JSON.writeValueAsBytes(descriptor);

        ByteBuffer dataHeader = ByteBuffer.allocate(DATA_FIXED_HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN);
        dataHeader.put(DATA_MAGIC);
        dataHeader.putShort((short) VERSION);
        dataHeader.putShort((short) DATA_FIXED_HEADER_LENGTH);
        dataHeader.putLong(startTimestampUs);
        dataHeader.putInt(descriptorBytes.length);
        dataHeader.putInt(0);
        dataHeader.flip();
        writeFully(data, dataHeader);
        writeFully(data, ByteBuffer.wrap(descriptorBytes));

        ByteBuffer indexHeader = ByteBuffer.allocate(INDEX_HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN);
        indexHeader.put(INDEX_MAGIC);
        indexHeader.putShort((short) VERSION);
        indexHeader.putShort((short) INDEX_ENTRY_LENGTH);
        indexHeader.putLong(startTimestampUs);
        indexHeader.flip();
        writeFully(index, indexHeader);
    }

    private void writeMarker(long dataBytes, long indexBytes) throws IOException {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("version", VERSION);
        marker.put("startTimestampUs", startTimestampUs);
        marker.put("endTimestampUs", endTimestampUs);
        marker.put("frameCount", frameCount);
        marker.put("keyFrameCount", keyFrameCount);
        marker.put("dataBytes", dataBytes);
        marker.put("indexBytes", indexBytes);
        byte[] markerBytes = JSON.writeValueAsBytes(marker);

        try (FileChannel markerChannel = FileChannel.open(markerPart,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeFully(markerChannel, ByteBuffer.wrap(markerBytes));
            markerChannel.force(true);
        }
        moveAtomically(markerPart, markerFile);
    }

    private static Path segmentDirectory(Path configuredRoot, StreamKey streamKey, long timestampUs) {
        Path root = configuredRoot.toAbsolutePath().normalize();
        long epochSecond = Math.floorDiv(timestampUs, 1_000_000L);
        long nanoAdjustment = Math.floorMod(timestampUs, 1_000_000L) * 1_000L;
        ZonedDateTime utc = ZonedDateTime.ofInstant(
                Instant.ofEpochSecond(epochSecond, nanoAdjustment), ZoneOffset.UTC);
        Path directory = root
                .resolve(RecordingPathEncoder.encode(streamKey.deviceId()))
                .resolve(Integer.toString(streamKey.channel()))
                .resolve(streamKey.streamKind().wireValue())
                .resolve(String.format("%04d", utc.getYear()))
                .resolve(String.format("%02d", utc.getMonthValue()))
                .resolve(String.format("%02d", utc.getDayOfMonth()))
                .resolve(String.format("%02d", utc.getHour()))
                .normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("Recording path escapes configured root");
        }
        return directory;
    }

    private static int flags(MediaFrameType type) {
        int result = 0;
        if (type == MediaFrameType.VIDEO_KEY) {
            result |= 1;
        }
        if (type == MediaFrameType.SPS || type == MediaFrameType.PPS || type == MediaFrameType.VPS) {
            result |= 1 << 1;
        }
        if (type == MediaFrameType.AUDIO_CONFIG) {
            result |= 1 << 2;
        }
        return result;
    }

    static int codecId(MediaCodec codec) {
        return switch (codec) {
            case H264 -> 1;
            case H265 -> 2;
            case AAC -> 3;
            case G711A -> 4;
            case TRANSPARENT, UNKNOWN -> 0;
        };
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private void ensureOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("Recording segment is not open: " + state);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The original write or commit failure is more useful to the caller.
        }
    }

    private enum State {
        OPEN,
        COMMITTED,
        ABORTED
    }
}
