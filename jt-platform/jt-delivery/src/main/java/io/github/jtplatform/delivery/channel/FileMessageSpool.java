package io.github.jtplatform.delivery.channel;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class FileMessageSpool {
    private static final String LAYOUT_FILE = ".stripes";
    private static final String MESSAGE_SUFFIX = ".json";

    private final Path directory;
    private final int stripes;
    private final ObjectMapper mapper = new ObjectMapper();

    FileMessageSpool(Path rootDirectory, String channelName, int stripes) {
        this.directory = rootDirectory.toAbsolutePath().normalize().resolve(channelName);
        this.stripes = stripes;
    }

    synchronized Snapshot snapshot() {
        if (!Files.isDirectory(directory)) {
            return new Snapshot(0, new int[stripes]);
        }
        verifyLayout();
        int[] counts = new int[stripes];
        long maximumSequence = 0;
        for (int stripe = 0; stripe < stripes; stripe++) {
            Path stripeDirectory = stripeDirectory(stripe);
            if (!Files.isDirectory(stripeDirectory)) {
                continue;
            }
            try (Stream<Path> paths = Files.list(stripeDirectory)) {
                for (Path path : paths.filter(FileMessageSpool::isMessageFile).toList()) {
                    counts[stripe]++;
                    maximumSequence = Math.max(maximumSequence, sequenceOf(path));
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("Unable to inspect delivery overflow at " + stripeDirectory, failure);
            }
        }
        return new Snapshot(maximumSequence, counts);
    }

    synchronized Entry append(long sequence, int stripe, MessageEnvelope envelope) {
        ensureLayout();
        Path stripeDirectory = stripeDirectory(stripe);
        try {
            Files.createDirectories(stripeDirectory);
            String fileName = "%020d-%s%s".formatted(sequence, eventHash(envelope.eventId()), MESSAGE_SUFFIX);
            Path target = stripeDirectory.resolve(fileName);
            Path temporary = stripeDirectory.resolve(fileName + ".tmp-" + UUID.randomUUID());
            byte[] content = encode(envelope);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            moveAtomically(temporary, target);
            return new Entry(sequence, stripe, target, envelope);
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to retain critical delivery event " + envelope.eventId(), failure);
        }
    }

    synchronized Entry peek(int stripe) {
        Path stripeDirectory = stripeDirectory(stripe);
        if (!Files.isDirectory(stripeDirectory)) {
            return null;
        }
        try (Stream<Path> paths = Files.list(stripeDirectory)) {
            Path first = paths.filter(FileMessageSpool::isMessageFile)
                    .min(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElse(null);
            if (first == null) {
                return null;
            }
            return new Entry(sequenceOf(first), stripe, first, decode(Files.readAllBytes(first)));
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to load retained delivery from " + stripeDirectory, failure);
        }
    }

    synchronized void complete(Entry entry) {
        try {
            Files.deleteIfExists(entry.path());
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to remove delivered overflow event "
                    + entry.envelope().eventId(), failure);
        }
    }

    private void ensureLayout() {
        try {
            Files.createDirectories(directory);
            Path layout = directory.resolve(LAYOUT_FILE);
            if (Files.exists(layout)) {
                verifyLayout();
                return;
            }
            Files.writeString(layout, Integer.toString(stripes), StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to initialize delivery overflow at " + directory, failure);
        }
    }

    private void verifyLayout() {
        Path layout = directory.resolve(LAYOUT_FILE);
        if (!Files.exists(layout)) {
            return;
        }
        try {
            int persistedStripes = Integer.parseInt(Files.readString(layout, StandardCharsets.US_ASCII).trim());
            if (persistedStripes != stripes) {
                throw new IllegalStateException("Delivery overflow at " + directory + " was created with "
                        + persistedStripes + " stripes but the current configuration uses " + stripes);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read delivery overflow layout at " + directory, failure);
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Invalid delivery overflow layout at " + layout, failure);
        }
    }

    private byte[] encode(MessageEnvelope envelope) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("eventId", envelope.eventId());
        value.put("deviceId", envelope.deviceId());
        value.put("messageId", envelope.messageId());
        value.put("serialNo", envelope.serialNo());
        value.put("protocolVersion", envelope.protocolVersion());
        value.put("receivedAt", envelope.receivedAt().toString());
        value.put("instanceId", envelope.instanceId());
        value.put("type", envelope.type().name());
        value.put("payload", envelope.payload());
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("Unable to encode retained delivery event " + envelope.eventId(),
                    failure);
        }
    }

    private MessageEnvelope decode(byte[] content) {
        try {
            JsonNode root = mapper.readTree(content);
            return new MessageEnvelope(
                    requiredText(root, "eventId"),
                    requiredText(root, "deviceId"),
                    requiredLong(root, "messageId"),
                    requiredInt(root, "serialNo"),
                    requiredText(root, "protocolVersion"),
                    Instant.parse(requiredText(root, "receivedAt")),
                    requiredText(root, "instanceId"),
                    MessageType.valueOf(requiredText(root, "type")),
                    payload(root.get("payload")));
        } catch (JacksonException failure) {
            throw new IllegalStateException("Unable to decode retained delivery event", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("Retained delivery event has no object payload");
        }
        return mapper.convertValue(node, Map.class);
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Retained delivery event is missing field " + field);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalStateException("Retained delivery event is missing field " + field);
        }
        return value.asInt();
    }

    private static long requiredLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw new IllegalStateException("Retained delivery event is missing field " + field);
        }
        return value.asLong();
    }

    private Path stripeDirectory(int stripe) {
        return directory.resolve("stripe-" + stripe);
    }

    private static boolean isMessageFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(MESSAGE_SUFFIX);
    }

    private static long sequenceOf(Path path) {
        String name = path.getFileName().toString();
        int separator = name.indexOf('-');
        if (separator <= 0) {
            throw new IllegalStateException("Invalid retained delivery file name: " + path);
        }
        try {
            return Long.parseLong(name.substring(0, separator));
        } catch (NumberFormatException failure) {
            throw new IllegalStateException("Invalid retained delivery sequence: " + path, failure);
        }
    }

    private static String eventHash(String eventId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(eventId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    record Snapshot(long maximumSequence, int[] counts) {
    }

    record Entry(long sequence, int stripe, Path path, MessageEnvelope envelope) {
    }
}
