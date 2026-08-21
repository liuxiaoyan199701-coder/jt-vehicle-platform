package io.github.jtconsole.domain;

public record RecordingUploadTask(
        String id,
        Long tenantId,
        String deviceId,
        Integer commandSerialNo,
        int channelNo,
        String startAt,
        String endAt,
        int mediaType,
        int streamType,
        int storageType,
        int conditionBits,
        String status,
        Integer resultCode,
        String credentialExpiresAt,
        String fileName,
        Long fileSize,
        String accessAddress,
        String contentType,
        String createdAt,
        String updatedAt,
        String completedAt) {
}
