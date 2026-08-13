package io.github.jtconsole.domain;

/**
 * 终端上传的多媒体文件元数据。{@code accessAddress} 由网关的
 * {@code multimedia-access-base-url} 决定，未配置时为空——前端需要做降级展示。
 */
public record MediaFile(
        Long id,
        String deviceId,
        Long fileId,
        String fileType,
        String fileFormat,
        String fileName,
        Long size,
        String accessAddress,
        Integer channelId,
        Integer eventCode,
        String capturedAt) {
}
