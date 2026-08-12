package org.yzh.web.service;

import io.github.jtplatform.signal.config.SignalProperties;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import org.yzh.protocol.jsatl12.DataPacket;
import org.yzh.protocol.jsatl12.T1210;
import org.yzh.protocol.jsatl12.T1211;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0801;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;

@Service
public class FileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileService.class);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final Path multimediaRoot;
    private final String multimediaAccessBaseUrl;
    private final Path alarmAttachmentRoot;

    public FileService(SignalProperties properties) {
        this.multimediaRoot = normalizeRoot(properties.getStorage().getMultimediaPath());
        this.multimediaAccessBaseUrl = normalizeBaseUrl(properties.getStorage().getMultimediaAccessBaseUrl());
        this.alarmAttachmentRoot = normalizeRoot(properties.getStorage().getAlarmAttachmentPath());
    }

    public void createDir(T1210 alarm) {
        Path directory = alarmDirectory(alarm);
        try {
            Files.createDirectories(directory);
            StringBuilder manifest = new StringBuilder();
            if (alarm.getItems() != null) {
                for (T1210.Item item : alarm.getItems()) {
                    manifest.append(safeFileName(item.getName())).append('\t').append(item.getSize()).append('\n');
                }
            }
            Files.writeString(directory.resolve("files.txt"), manifest, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to create alarm attachment directory", error);
        }
    }

    public void writeFile(T1210 alarm, DataPacket packet, boolean last) {
        Path destination = attachmentPath(alarm, packet.getName());
        Path partial = sibling(destination, ".part");
        Path rangeLog = sibling(destination, ".ranges");
        ByteBuf data = packet.getData();
        if (data == null) {
            return;
        }
        try {
            Files.createDirectories(destination.getParent());
            try (FileChannel file = FileChannel.open(partial,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                int length = Math.min(packet.getLength(), data.readableBytes());
                data.getBytes(data.readerIndex(), file, packet.getOffset(), length);
                appendRange(rangeLog, packet.getOffset(), length);
                if (last) {
                    file.force(false);
                }
            }
        } catch (IOException error) {
            LOGGER.error("Unable to write alarm attachment {}", destination, error);
        }
    }

    public void writeFileSingle(T1210 alarm, DataPacket packet) {
        Path destination = attachmentPath(alarm, packet.getName());
        ByteBuf data = packet.getData();
        if (data == null) {
            return;
        }
        try {
            Files.createDirectories(destination.getParent());
            try (FileChannel file = FileChannel.open(destination,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                data.getBytes(data.readerIndex(), file, packet.getOffset(), data.readableBytes());
                file.force(false);
            }
        } catch (IOException error) {
            LOGGER.error("Unable to write alarm attachment {}", destination, error);
        }
    }

    public int[] checkFile(T1210 alarm, T1211 fileInfo) {
        Path destination = attachmentPath(alarm, fileInfo.getName());
        if (isComplete(destination, fileInfo.getSize())) {
            return null;
        }
        Path partial = sibling(destination, ".part");
        Path rangeLog = sibling(destination, ".ranges");
        List<long[]> ranges = readRanges(rangeLog);
        List<Integer> missing = missingRanges(ranges, fileInfo.getSize());
        if (!missing.isEmpty()) {
            return missing.stream().mapToInt(Integer::intValue).toArray();
        }
        try {
            moveCompleted(partial, destination);
            Files.deleteIfExists(rangeLog);
            return null;
        } catch (IOException error) {
            LOGGER.error("Unable to finalize alarm attachment {}", destination, error);
            return new int[] {0, Math.toIntExact(Math.min(fileInfo.getSize(), Integer.MAX_VALUE))};
        }
    }

    public Optional<StoredMediaFile> saveMediaFile(T0801 message) {
        ByteBuf packet = message.getPacket();
        if (packet == null) {
            return Optional.empty();
        }
        Path temporary = null;
        try {
            String deviceId = message.getClientId();
            if (message.getSession() != null) {
                DeviceDO device = message.getSession().getAttribute(SessionKey.Device);
                if (device != null && device.getDeviceId() != null && !device.getDeviceId().isBlank()) {
                    deviceId = device.getDeviceId();
                }
            }
            T0200 location = message.getLocation();
            LocalDateTime time = location == null || location.getDeviceTime() == null
                    ? LocalDateTime.now(ZoneOffset.UTC)
                    : location.getDeviceTime();
            String filename = mediaType(message.getType()) + '_' + FILE_TIME.format(time) + '_'
                    + message.getChannelId() + '_' + message.getEvent() + '_' + message.getId() + '.'
                    + mediaSuffix(message.getFormat());
            String deviceSegment = safeSegment(deviceId);
            Path directory = resolveWithin(multimediaRoot, deviceSegment);
            Files.createDirectories(directory);
            Path destination = resolveWithin(directory, safeFileName(filename));
            long size = packet.readableBytes();
            if (isComplete(destination, size)) {
                return Optional.of(storedMediaFile(message, destination, deviceSegment, size, false));
            }

            temporary = Files.createTempFile(directory, '.' + filename + '.', ".part");
            try (FileChannel file = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                packet.getBytes(packet.readerIndex(), file, 0, packet.readableBytes());
                file.force(false);
            }
            boolean newlyStored = moveNew(temporary, destination);
            if (!newlyStored) {
                Files.deleteIfExists(temporary);
            }
            temporary = null;
            return Optional.of(storedMediaFile(message, destination, deviceSegment, size, newlyStored));
        } catch (IOException error) {
            LOGGER.error("Unable to store terminal multimedia payload", error);
            return Optional.empty();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException error) {
                    LOGGER.warn("Unable to remove partial multimedia file {}", temporary, error);
                }
            }
            ReferenceCountUtil.safeRelease(packet);
            message.setPacket(null);
        }
    }

    public Optional<Path> resolveMediaFile(String deviceId, String fileName) {
        requireLookupSegment(deviceId, "deviceId");
        requireLookupSegment(fileName, "fileName");
        Path candidate = resolveWithin(resolveWithin(multimediaRoot, deviceId), fileName);
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            Path realRoot = multimediaRoot.toRealPath();
            Path realFile = candidate.toRealPath();
            return realFile.startsWith(realRoot) ? Optional.of(realFile) : Optional.empty();
        } catch (IOException error) {
            LOGGER.warn("Unable to resolve multimedia file {}", candidate, error);
            return Optional.empty();
        }
    }

    private StoredMediaFile storedMediaFile(
            T0801 message,
            Path destination,
            String deviceSegment,
            long size,
            boolean newlyStored) {
        String fileName = destination.getFileName().toString();
        String accessPath = "/files/multimedia/"
                + UriUtils.encodePathSegment(deviceSegment, StandardCharsets.UTF_8)
                + '/'
                + UriUtils.encodePathSegment(fileName, StandardCharsets.UTF_8);
        return new StoredMediaFile(
                destination,
                Integer.toUnsignedLong(message.getId()),
                mediaType(message.getType()),
                mediaFormat(message.getFormat()),
                fileName,
                size,
                multimediaAccessBaseUrl + accessPath,
                newlyStored);
    }

    private Path alarmDirectory(T1210 alarm) {
        LocalDateTime time = alarm.getDateTime() == null ? LocalDateTime.now(ZoneOffset.UTC) : alarm.getDateTime();
        String name = safeSegment(alarm.getClientId()) + '_' + FILE_TIME.format(time) + '_' + alarm.getSerialNo();
        return resolveWithin(alarmAttachmentRoot, name);
    }

    private Path attachmentPath(T1210 alarm, String fileName) {
        return resolveWithin(alarmDirectory(alarm), safeFileName(fileName));
    }

    private static void appendRange(Path path, int offset, int length) throws IOException {
        ByteBuffer range = ByteBuffer.allocate(8).putInt(offset).putInt(length).flip();
        try (FileChannel log = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (range.hasRemaining()) {
                log.write(range);
            }
        }
    }

    private static List<long[]> readRanges(Path path) {
        if (!Files.isRegularFile(path)) {
            return new ArrayList<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            List<long[]> ranges = new ArrayList<>(bytes.length / 8);
            while (buffer.remaining() >= 8) {
                long start = Integer.toUnsignedLong(buffer.getInt());
                long length = Integer.toUnsignedLong(buffer.getInt());
                ranges.add(new long[] {start, start + length});
            }
            return ranges;
        } catch (IOException error) {
            LOGGER.error("Unable to read attachment range log {}", path, error);
            return new ArrayList<>();
        }
    }

    private static List<Integer> missingRanges(List<long[]> ranges, long expectedSize) {
        ranges.sort(Comparator.comparingLong(range -> range[0]));
        List<Integer> result = new ArrayList<>();
        long cursor = 0;
        for (long[] range : ranges) {
            if (range[0] > cursor) {
                addRange(result, cursor, range[0] - cursor);
            }
            cursor = Math.max(cursor, range[1]);
        }
        if (cursor < expectedSize) {
            addRange(result, cursor, expectedSize - cursor);
        }
        return result;
    }

    private static void addRange(List<Integer> result, long offset, long length) {
        result.add(Math.toIntExact(Math.min(offset, Integer.MAX_VALUE)));
        result.add(Math.toIntExact(Math.min(length, Integer.MAX_VALUE)));
    }

    private static boolean isComplete(Path destination, long expectedSize) {
        try {
            return Files.isRegularFile(destination) && Files.size(destination) == expectedSize;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void moveCompleted(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean moveNew(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (AtomicMoveNotSupportedException ignored) {
            try {
                Files.move(source, destination);
                return true;
            } catch (java.nio.file.FileAlreadyExistsException duplicate) {
                return false;
            }
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            return false;
        }
    }

    private static Path sibling(Path path, String suffix) {
        return path.resolveSibling(path.getFileName() + suffix);
    }

    private static Path normalizeRoot(Path root) {
        return Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static Path resolveWithin(Path root, String child) {
        Path result = root.resolve(child).normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes configured storage root");
        }
        return result;
    }

    private static String safeFileName(String value) {
        String normalized = Objects.requireNonNullElse(value, "unnamed")
                .replace('\\', '/')
                .replaceAll("[\\x00-\\x1f:*?\"<>|]", "_");
        int separator = normalized.lastIndexOf('/');
        return safeSegment(separator >= 0 ? normalized.substring(separator + 1) : normalized);
    }

    private static String safeSegment(String value) {
        String result = Objects.requireNonNullElse(value, "unknown").strip()
                .replaceAll("[\\x00-\\x1f\\\\/:*?\"<>|]", "_");
        if (result.isEmpty() || result.equals(".") || result.equals("..")) {
            return "unknown";
        }
        return result;
    }

    private static void requireLookupSegment(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()
                || normalized.equals(".")
                || normalized.equals("..")
                || !normalized.equals(value)
                || normalized.matches(".*[\\x00-\\x1f\\\\/:*?\"<>|].*")) {
            throw new IllegalArgumentException(name + " contains an invalid path segment");
        }
    }

    private static String mediaType(int type) {
        return switch (type) {
            case 0 -> "image";
            case 1 -> "audio";
            case 2 -> "video";
            default -> "unknown";
        };
    }

    private static String mediaSuffix(int format) {
        return switch (format) {
            case 0 -> "jpg";
            case 1 -> "tif";
            case 2 -> "mp3";
            case 3 -> "wav";
            case 4 -> "wmv";
            default -> "bin";
        };
    }

    private static String mediaFormat(int format) {
        return switch (format) {
            case 0 -> "jpeg";
            case 1 -> "tiff";
            case 2 -> "mp3";
            case 3 -> "wav";
            case 4 -> "wmv";
            default -> "binary";
        };
    }
}
