package io.github.jtconsole.ai.vision;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 送检前的图片预处理：过大就缩小，并统一成 JPEG。
 *
 * <p><b>为什么必须缩</b>：视觉模型按图像面积折算 token。一张手机原图 4000×3000，比 1280 长边的
 * 版本贵一个数量级，而对「这是什么车、路上有什么」这类问题，多出来的像素不会让答案更准。
 * 抓拍原图最大也就 D1（704×576），本来就在阈值内——这个上限主要是挡用户上传的照片。
 *
 * <p><b>为什么统一成 JPEG</b>：PNG 对照片类内容体积可以是 JPEG 的数倍，而 base64 还要再放大
 * 1/3。截图类内容会因此损失一点锐度，但换来的是可预期的请求体积。
 *
 * <p>服务端跑 AWT 需要 headless 模式，否则在无桌面环境会抛 {@code HeadlessException}。
 * 本类只用 {@link BufferedImage} 与 {@link ImageIO}，二者在 headless 下均可用。
 */
public final class ImagePreparer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImagePreparer.class);

    private static final String JPEG = "image/jpeg";

    private ImagePreparer() {
    }

    /**
     * 解码、按需缩放、重新编码。
     *
     * @param raw          原始字节
     * @param maxEdge      长边像素上限，小于等于 0 表示不缩放
     * @param label        给模型的上下文标签
     * @return 可直接送检的图片
     * @throws VisionUnavailableException 无法解码时抛出——那通常意味着文件根本不是图片
     */
    public static VisionImage prepare(byte[] raw, int maxEdge, String label) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException failure) {
            throw new VisionUnavailableException("图片无法解码：" + failure.getMessage(), failure);
        }
        if (source == null) {
            // ImageIO 对不认识的格式返回 null 而不是抛异常，这个分支比看起来常见。
            throw new VisionUnavailableException("无法识别的图片格式");
        }

        int longest = Math.max(source.getWidth(), source.getHeight());
        BufferedImage target = (maxEdge > 0 && longest > maxEdge)
                ? scale(source, (double) maxEdge / longest)
                : source;

        try {
            return new VisionImage(encodeJpeg(target), JPEG, label);
        } catch (IOException failure) {
            throw new VisionUnavailableException("图片重编码失败：" + failure.getMessage(), failure);
        }
    }

    private static BufferedImage scale(BufferedImage source, double ratio) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        // 目标固定为 RGB：源图可能带 alpha，而 JPEG 不支持透明通道，直接编码会得到
        // 一张颜色错乱的图（红蓝互换是最常见的表现）。先合成到不透明画布上。
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        LOGGER.debug("图片缩放 {}x{} -> {}x{}",
                source.getWidth(), source.getHeight(), width, height);
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        BufferedImage opaque = image;
        if (image.getTransparency() != BufferedImage.OPAQUE) {
            opaque = new BufferedImage(
                    image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = opaque.createGraphics();
            try {
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!ImageIO.write(opaque, "jpg", buffer)) {
            throw new IOException("当前 JRE 没有可用的 JPEG 编码器");
        }
        return buffer.toByteArray();
    }
}
