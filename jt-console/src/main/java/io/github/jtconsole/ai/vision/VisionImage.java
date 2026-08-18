package io.github.jtconsole.ai.vision;

import java.util.Objects;
import java.util.Set;

/**
 * 一张送去识别的图片。
 *
 * @param bytes    图片字节。**已经过缩放**——缩放在取图那一侧完成，本记录不负责
 * @param mimeType 形如 {@code image/jpeg}，随 data URI 一起送出
 * @param label    给模型的上下文标签（如「粤B12345 通道1 2026-08-18 14:23 的抓拍」）。
 *                 有它，模型的描述才能和具体那台车对上号；可为 null
 */
public record VisionImage(byte[] bytes, String mimeType, String label) {

    /**
     * 允许送检的图片类型。
     *
     * <p>白名单而不是黑名单：视觉模型只认这几种，放行别的类型只会换来一个上游 400，
     * 而那时用户已经等了一次上传和一次网络往返。SVG 刻意不在列——它是可执行文档，
     * 不是位图。
     */
    public static final Set<String> ALLOWED_MIME_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    public VisionImage {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(mimeType, "mimeType");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("图片内容为空");
        }
        if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("不支持的图片类型：" + mimeType);
        }
    }
}
