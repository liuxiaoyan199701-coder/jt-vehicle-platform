package io.github.jtplatform.media.ftp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;

/** Resolves public recording-upload paths without allowing traversal outside a task home. */
public final class RecordingUploadFileStore {
    private final Path root;

    public RecordingUploadFileStore(RecordingFtpProperties properties) {
        this.root = Objects.requireNonNull(properties.getRoot(), "root").toAbsolutePath().normalize();
    }

    public Path taskHome(String taskId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(required(taskId, "taskId").getBytes(StandardCharsets.UTF_8));
        return root.resolve(encoded).normalize();
    }

    public Path resolve(String taskId, String fileName) {
        String safeName = required(fileName, "fileName");
        if (!Path.of(safeName).getFileName().toString().equals(safeName)) {
            throw new IllegalArgumentException("fileName must not contain a path");
        }
        Path home = taskHome(taskId);
        Path result = home.resolve(safeName).normalize();
        if (!result.startsWith(home)) throw new IllegalArgumentException("path escapes task home");
        return result;
    }

    private static String required(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
