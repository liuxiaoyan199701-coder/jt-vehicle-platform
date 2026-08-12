package org.yzh.web.service;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record StoredMediaFile(
        Path path,
        long fileId,
        String fileType,
        String fileFormat,
        String fileName,
        long size,
        String accessAddress,
        boolean newlyStored) {

    public StoredMediaFile {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        fileType = requireText(fileType, "fileType");
        fileFormat = requireText(fileFormat, "fileFormat");
        fileName = requireText(fileName, "fileName");
        accessAddress = requireText(accessAddress, "accessAddress");
        if (fileId < 0 || fileId > 0xffff_ffffL) {
            throw new IllegalArgumentException("fileId must be in range 0..4294967295");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }

    public Map<String, Object> deliveryMetadata() {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fileId", fileId);
        metadata.put("fileType", fileType);
        metadata.put("fileFormat", fileFormat);
        metadata.put("fileName", fileName);
        metadata.put("size", size);
        metadata.put("accessAddress", accessAddress);
        return Collections.unmodifiableMap(metadata);
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
