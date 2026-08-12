package io.github.jtplatform.media.recording;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.config.RecordingProperties;
import io.github.jtplatform.media.frame.MediaCodec;
import io.github.jtplatform.media.frame.MediaFrame;
import io.github.jtplatform.media.frame.MediaFrameType;
import io.github.jtplatform.media.sink.SinkRegistry;
import io.github.jtplatform.media.sink.WebSocketRawSink;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordSinkTest {
    private static final long START_US = 1_700_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesH264BootstrapIndexAndCommitMarker() throws Exception {
        Path root = temporaryDirectory.resolve("recordings");
        RecordSink sink = new RecordSink(properties(root, true, false, Duration.ofSeconds(30)));
        StreamKey key = new StreamKey("../unsafe/device", 1, StreamKind.MAIN);
        byte[] sps = {0, 0, 0, 1, 0x67, 0x01};
        byte[] pps = {0, 0, 0, 1, 0x68, 0x02};
        byte[] keyPayload = {0, 0, 0, 1, 0x65, 0x03};
        byte[] deltaPayload = {0, 0, 0, 1, 0x41, 0x04};

        sink.accept(frame(key, MediaFrameType.SPS, MediaCodec.H264, START_US - 2, sps));
        sink.accept(frame(key, MediaFrameType.PPS, MediaCodec.H264, START_US - 1, pps));
        sink.accept(frame(key, MediaFrameType.VIDEO_KEY, MediaCodec.H264, START_US, keyPayload));
        sink.accept(frame(key, MediaFrameType.VIDEO_DELTA, MediaCodec.H264, START_US + 1, deltaPayload));
        sink.close(key);

        Path data = onlyFile(root, ".jtr");
        assertTrue(data.normalize().startsWith(root.normalize()));
        assertFalse(data.toString().contains("unsafe"));
        assertTrue(Files.isRegularFile(sibling(data, ".jti")));
        assertTrue(Files.isRegularFile(sibling(data, ".ok")));
        assertEquals(0, filesEndingWith(root, ".part").size());

        RecordingSegmentInspection inspection = RecordingSegmentInspector.inspect(data);
        assertEquals(RecordingSegmentStatus.COMMITTED, inspection.status());
        assertEquals(START_US, inspection.startTimestampUs());
        assertEquals(START_US + 1, inspection.endTimestampUs());
        assertEquals(4, inspection.frameCount());
        assertEquals(1, inspection.keyFrameCount());

        List<StoredRecord> records = records(data);
        assertEquals(List.of(
                MediaFrameType.SPS.wireValue(),
                MediaFrameType.PPS.wireValue(),
                MediaFrameType.VIDEO_KEY.wireValue(),
                MediaFrameType.VIDEO_DELTA.wireValue()),
                records.stream().map(StoredRecord::frameType).toList());
        assertArrayEquals(sps, records.get(0).payload());
        assertArrayEquals(pps, records.get(1).payload());
        assertArrayEquals(keyPayload, records.get(2).payload());
        assertArrayEquals(deltaPayload, records.get(3).payload());
        assertEquals(RecordingSegmentWriter.INDEX_HEADER_LENGTH
                        + records.size() * RecordingSegmentWriter.INDEX_ENTRY_LENGTH,
                Files.size(sibling(data, ".jti")));
    }

    @Test
    void keepsH265PayloadUnmodifiedAndRotatesAtConfiguredDuration() throws Exception {
        Path root = temporaryDirectory.resolve("h265");
        RecordSink sink = new RecordSink(properties(root, true, false, Duration.ofNanos(10_000)));
        StreamKey key = new StreamKey("device-2", 2, StreamKind.SUB);
        byte[] firstKey = {0, 0, 1, 0x26, 0x01, 0x55};
        byte[] secondKey = {0, 0, 1, 0x26, 0x01, 0x66};

        sink.accept(frame(key, MediaFrameType.VPS, MediaCodec.H265, START_US - 3,
                new byte[] {0, 0, 1, 0x40, 1}));
        sink.accept(frame(key, MediaFrameType.SPS, MediaCodec.H265, START_US - 2,
                new byte[] {0, 0, 1, 0x42, 1}));
        sink.accept(frame(key, MediaFrameType.PPS, MediaCodec.H265, START_US - 1,
                new byte[] {0, 0, 1, 0x44, 1}));
        sink.accept(frame(key, MediaFrameType.VIDEO_KEY, MediaCodec.H265, START_US, firstKey));
        sink.accept(frame(key, MediaFrameType.VIDEO_KEY, MediaCodec.H265, START_US + 11, secondKey));
        sink.close(key);

        List<Path> segments = filesEndingWith(root, ".jtr");
        assertEquals(2, segments.size());
        for (Path segment : segments) {
            assertEquals(RecordingSegmentStatus.COMMITTED,
                    RecordingSegmentInspector.inspect(segment).status());
        }
        List<byte[]> keyPayloads = segments.stream()
                .flatMap(segment -> uncheckedRecords(segment).stream())
                .filter(record -> record.frameType() == MediaFrameType.VIDEO_KEY.wireValue())
                .map(StoredRecord::payload)
                .toList();
        assertTrue(keyPayloads.stream().anyMatch(payload -> java.util.Arrays.equals(payload, firstKey)));
        assertTrue(keyPayloads.stream().anyMatch(payload -> java.util.Arrays.equals(payload, secondKey)));
    }

    @Test
    void realtimeAndPlaybackSwitchesAreIndependentAndDefaultIsDisabled() throws Exception {
        StreamKey realtime = new StreamKey("device-3", 1, StreamKind.MAIN);
        StreamKey playback = new StreamKey("device-3", 1, StreamKind.PLAYBACK);
        MediaFrame realtimeAudio = frame(
                realtime, MediaFrameType.AUDIO, MediaCodec.G711A, START_US, new byte[] {1});
        MediaFrame playbackAudio = frame(
                playback, MediaFrameType.AUDIO, MediaCodec.G711A, START_US, new byte[] {2});

        Path disabledRoot = temporaryDirectory.resolve("disabled");
        RecordSink disabled = new RecordSink(properties(disabledRoot, false, false, Duration.ofSeconds(30)));
        disabled.accept(realtimeAudio);
        disabled.accept(playbackAudio);
        disabled.close();
        assertFalse(Files.exists(disabledRoot));

        Path realtimeRoot = temporaryDirectory.resolve("realtime");
        RecordSink realtimeOnly = new RecordSink(properties(
                realtimeRoot, true, false, Duration.ofSeconds(30)));
        realtimeOnly.accept(realtimeAudio);
        realtimeOnly.accept(playbackAudio);
        realtimeOnly.close();
        assertEquals(1, filesEndingWith(realtimeRoot, ".ok").size());
        assertTrue(onlyFile(realtimeRoot, ".ok").toString().contains("main"));

        Path playbackRoot = temporaryDirectory.resolve("playback");
        RecordSink playbackOnly = new RecordSink(properties(
                playbackRoot, false, true, Duration.ofSeconds(30)));
        playbackOnly.accept(realtimeAudio);
        playbackOnly.accept(playbackAudio);
        playbackOnly.close();
        assertEquals(1, filesEndingWith(playbackRoot, ".ok").size());
        assertTrue(onlyFile(playbackRoot, ".ok").toString().contains("playback"));
    }

    @Test
    void manualTriggerUsesCachedParametersAndStopsImmediately() throws Exception {
        Path root = temporaryDirectory.resolve("manual");
        RecordingProperties properties = properties(root, true, false, Duration.ofSeconds(30));
        properties.setContinuousEnabled(false);
        properties.setManualEnabled(true);
        RecordSink sink = new RecordSink(properties);
        StreamKey key = new StreamKey("device-manual", 1, StreamKind.MAIN);
        byte[] firstKey = {0, 0, 0, 1, 0x65, 0x01};
        byte[] recordedKey = {0, 0, 0, 1, 0x65, 0x02};

        sink.accept(frame(key, MediaFrameType.SPS, MediaCodec.H264, START_US - 2,
                new byte[] {0, 0, 0, 1, 0x67, 0x01}));
        sink.accept(frame(key, MediaFrameType.PPS, MediaCodec.H264, START_US - 1,
                new byte[] {0, 0, 0, 1, 0x68, 0x01}));
        sink.accept(frame(key, MediaFrameType.VIDEO_KEY, MediaCodec.H264, START_US, firstKey));
        assertFalse(Files.exists(root));

        assertTrue(sink.startManual(key));
        sink.accept(frame(key, MediaFrameType.VIDEO_KEY, MediaCodec.H264, START_US + 1, recordedKey));
        assertTrue(sink.stopManual(key));
        assertFalse(sink.isRecording(key));
        sink.accept(frame(key, MediaFrameType.VIDEO_KEY, MediaCodec.H264, START_US + 2, firstKey));
        sink.onStreamClosed(key);

        List<StoredRecord> stored = records(onlyFile(root, ".jtr"));
        List<byte[]> keyFrames = stored.stream()
                .filter(record -> record.frameType() == MediaFrameType.VIDEO_KEY.wireValue())
                .map(StoredRecord::payload)
                .toList();
        assertEquals(1, keyFrames.size());
        assertArrayEquals(recordedKey, keyFrames.getFirst());
    }

    @Test
    void triggerReasonsComposeAndStreamEndClearsTransientTriggers() throws Exception {
        Path root = temporaryDirectory.resolve("combined-triggers");
        RecordingProperties properties = properties(root, true, false, Duration.ofSeconds(30));
        properties.setContinuousEnabled(false);
        properties.setManualEnabled(true);
        properties.setAlarmEnabled(true);
        RecordSink sink = new RecordSink(properties);
        StreamKey key = new StreamKey("device-alarm", 2, StreamKind.SUB);
        StreamKey otherDevice = new StreamKey("other-device", 2, StreamKind.SUB);

        assertTrue(sink.startManual(key));
        assertEquals(1, sink.triggerAlarm("device-alarm", List.of(key, otherDevice)));
        sink.accept(frame(key, MediaFrameType.AUDIO, MediaCodec.G711A, START_US, new byte[] {1}));
        assertTrue(sink.stopManual(key));
        assertTrue(sink.isRecording(key));
        sink.accept(frame(key, MediaFrameType.AUDIO, MediaCodec.G711A, START_US + 1, new byte[] {2}));

        sink.onStreamClosed(key);

        assertFalse(sink.isRecording(key));
        assertEquals(List.of(1, 2), records(onlyFile(root, ".jtr")).stream()
                .map(record -> Byte.toUnsignedInt(record.payload()[0]))
                .toList());
    }

    @Test
    void completedSegmentSurvivesAlongsideTruncatedAndUncommittedSegments() throws Exception {
        Path root = temporaryDirectory.resolve("recovery");
        RecordSink sink = new RecordSink(properties(root, true, false, Duration.ofSeconds(30)));
        StreamKey key = new StreamKey("device-4", 1, StreamKind.MAIN);
        sink.accept(frame(key, MediaFrameType.AUDIO, MediaCodec.G711A, START_US, new byte[] {1, 2, 3}));
        sink.close(key);
        Path committed = onlyFile(root, ".jtr");

        Path corruptData = committed.resolveSibling("corrupt.jtr");
        Path corruptIndex = committed.resolveSibling("corrupt.jti");
        Path corruptMarker = committed.resolveSibling("corrupt.ok");
        Files.copy(committed, corruptData, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(sibling(committed, ".jti"), corruptIndex, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(sibling(committed, ".ok"), corruptMarker, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel channel = FileChannel.open(corruptData, StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 1);
        }
        Path unfinished = committed.resolveSibling("unfinished.jtr.part");
        Files.write(unfinished, new byte[] {'J', 'T', 'R', '1'});

        assertEquals(RecordingSegmentStatus.COMMITTED,
                RecordingSegmentInspector.inspect(committed).status());
        assertEquals(RecordingSegmentStatus.CORRUPT,
                RecordingSegmentInspector.inspect(corruptData).status());
        assertEquals(RecordingSegmentStatus.INCOMPLETE,
                RecordingSegmentInspector.inspect(unfinished).status());
        assertArrayEquals(new byte[] {1, 2, 3}, records(committed).getFirst().payload());
    }

    @Test
    void diskFailureDoesNotPreventWebSocketDistribution() throws Exception {
        Path invalidRoot = temporaryDirectory.resolve("not-a-directory");
        Files.write(invalidRoot, new byte[] {1});
        RecordSink recordSink = new RecordSink(properties(
                invalidRoot, true, false, Duration.ofSeconds(30)));
        WebSocketRawSink webSocketSink = new WebSocketRawSink();
        SinkRegistry registry = new SinkRegistry();
        registry.register(recordSink);
        registry.register(webSocketSink);
        StreamKey key = new StreamKey("device-5", 1, StreamKind.MAIN);
        EmbeddedChannel subscriber = new EmbeddedChannel();
        webSocketSink.subscribe(key, subscriber);
        drainAndRelease(subscriber);

        registry.dispatch(frame(
                key, MediaFrameType.AUDIO, MediaCodec.G711A, START_US, new byte[] {7, 8, 9}));
        subscriber.runPendingTasks();

        boolean binaryReceived = false;
        Object message;
        while ((message = subscriber.readOutbound()) != null) {
            binaryReceived |= message instanceof BinaryWebSocketFrame;
            ReferenceCountUtil.release(message);
        }
        assertTrue(binaryReceived);
        subscriber.finishAndReleaseAll();
    }

    private static RecordingProperties properties(
            Path root, boolean realtime, boolean playback, Duration segmentDuration) {
        RecordingProperties properties = new RecordingProperties();
        properties.setRoot(root);
        properties.setRealtimeEnabled(realtime);
        properties.setPlaybackEnabled(playback);
        properties.setSegmentDuration(segmentDuration);
        return properties;
    }

    private static MediaFrame frame(
            StreamKey key,
            MediaFrameType type,
            MediaCodec codec,
            long timestampUs,
            byte[] payload) {
        return new MediaFrame(key, type, codec, timestampUs, payload);
    }

    private static List<Path> filesEndingWith(Path root, String suffix) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static Path onlyFile(Path root, String suffix) throws IOException {
        List<Path> files = filesEndingWith(root, suffix);
        assertEquals(1, files.size());
        return files.getFirst();
    }

    private static Path sibling(Path data, String extension) {
        String filename = data.getFileName().toString();
        int suffix = filename.lastIndexOf('.');
        return data.resolveSibling(filename.substring(0, suffix) + extension);
    }

    private static List<StoredRecord> uncheckedRecords(Path data) {
        try {
            return records(data);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static List<StoredRecord> records(Path data) throws IOException {
        byte[] bytes = Files.readAllBytes(data);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        buffer.position(16);
        int descriptorLength = buffer.getInt();
        buffer.position(RecordingSegmentWriter.DATA_FIXED_HEADER_LENGTH + descriptorLength);
        List<StoredRecord> records = new ArrayList<>();
        while (buffer.hasRemaining()) {
            int recordLength = buffer.getInt();
            int frameType = Byte.toUnsignedInt(buffer.get());
            buffer.get();
            buffer.getShort();
            buffer.getLong();
            int payloadLength = buffer.getInt();
            assertEquals(recordLength, payloadLength);
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);
            records.add(new StoredRecord(frameType, payload));
        }
        return records;
    }

    private static void drainAndRelease(EmbeddedChannel channel) {
        Object message;
        while ((message = channel.readOutbound()) != null) {
            ReferenceCountUtil.release(message);
        }
    }

    private record StoredRecord(int frameType, byte[] payload) {
    }
}
