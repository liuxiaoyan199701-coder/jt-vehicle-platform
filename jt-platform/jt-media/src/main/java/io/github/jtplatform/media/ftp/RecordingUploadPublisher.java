package io.github.jtplatform.media.ftp;

import io.github.jtplatform.delivery.model.MessageEnvelope;
import io.github.jtplatform.delivery.model.MessageType;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Publishes metadata after MINA has atomically finished a STOR command. */
public final class RecordingUploadPublisher {
    static final long RECORDING_UPLOAD_FILE = 0xF106L;
    private static final Logger LOGGER = LoggerFactory.getLogger(RecordingUploadPublisher.class);

    private final MessagePublisher publisher;
    private final RecordingFtpProperties properties;
    private final Clock clock;
    private final String instanceId;
    private final String fallbackBaseUrl;

    public RecordingUploadPublisher(MessagePublisher publisher, RecordingFtpProperties properties,
                                    Clock clock, String instanceId, String serverAddress,
                                    int managementPort) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.fallbackBaseUrl = "http://" + serverAddress + ':' + managementPort;
    }

    void publish(String taskId, String deviceId, Path file) {
        try {
            Path normalized = file.toAbsolutePath().normalize();
            String fileName = normalized.getFileName().toString();
            long size = Files.size(normalized);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", taskId);
            payload.put("fileName", fileName);
            payload.put("size", size);
            payload.put("accessAddress", accessAddress(taskId, fileName));
            payload.put("contentType", contentType(fileName));
            MessageEnvelope envelope = new MessageEnvelope(
                    "recording-upload:" + taskId + ':' + fileName + ':' + size,
                    deviceId, RECORDING_UPLOAD_FILE, 0, "recording-upload-v1",
                    clock.instant(), instanceId, MessageType.RECORDING_METADATA, payload);
            publisher.publish(envelope);
        } catch (Exception failure) {
            LOGGER.error("Unable to publish recording upload metadata for task {}", taskId, failure);
        }
    }

    private String accessAddress(String taskId, String fileName) {
        String configured = properties.getAccessBaseUrl();
        String base = configured == null || configured.isBlank() ? fallbackBaseUrl : trimSlash(configured);
        return base + "/recording-uploads/" + encode(taskId) + '/' + encode(fileName);
    }

    private static String contentType(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "application/octet-stream";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
}
