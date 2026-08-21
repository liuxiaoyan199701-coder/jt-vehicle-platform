package io.github.jtconsole.domain;

/** OTA 升级包元数据。包文件落盘，这里只存引用与摘要。 */
public record UpgradePackage(
        Long id,
        String name,
        String version,
        String makerId,
        String fileName,
        String filePath,
        long sizeBytes,
        String sha256,
        String createdAt,
        String updatedAt) {
}
