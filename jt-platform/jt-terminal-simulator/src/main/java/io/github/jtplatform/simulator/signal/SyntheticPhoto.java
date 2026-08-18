package io.github.jtplatform.simulator.signal;

import io.github.jtplatform.simulator.config.TerminalTime;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import javax.imageio.ImageIO;

/**
 * 没有摄像头时合成一张抓拍。
 *
 * <p><b>为什么要有</b>：模拟器的定位是「不需要真实硬件也能跑通端到端」——行程模拟就不需要真实
 * GPS。但抓拍此前硬依赖一个真实摄像头，且走的是 Windows 专有的 dshow，在没有摄像头的机器上
 * 直接抛「camera is not configured」，整条拍照链路就验不了。
 *
 * <p><b>为什么用纯 Java 而不是 FFmpeg 的 lavfi</b>：FFmpeg 的 {@code drawtext} 滤镜依赖
 * libfreetype 和一个外部字体文件，Windows 上的构建经常缺，而且是在运行时才失败。模拟器本来就是
 * JavaFX 应用，{@code java.desktop} 已在依赖里，{@link BufferedImage} 这条路无外部依赖、
 * 跨平台、输出确定，还快得多。
 *
 * <p>图上刻意写满信息（车牌、通道、第几张、时间戳），是为了让验收时**肉眼就能核对**平台上收到的
 * 那张图确实来自这台车的这一次抓拍——否则几张灰底图摆在一起根本分不清谁是谁。
 */
final class SyntheticPhoto {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 每张图换一个色相，避免连拍出来的几张长得一模一样。 */
    private static final Color[] TINTS = {
        new Color(0x1D6FB8), new Color(0x2E7D5B), new Color(0xA05A1E),
        new Color(0x6B4E9E), new Color(0xB03A48), new Color(0x2A7B8C),
    };

    private SyntheticPhoto() {
    }

    /**
     * 生成一张 JPEG。
     *
     * @param width    像素宽，取自 0x8801 的分辨率编码
     * @param height   像素高
     * @param plateNo  车牌号，写在图上
     * @param channel  通道号
     * @param index    本次连拍中的第几张，从 1 开始
     * @param total    本次连拍共几张
     */
    static byte[] render(
            int width, int height, String plateNo, int channel, int index, int total)
            throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color tint = TINTS[Math.floorMod(index - 1, TINTS.length)];
            g.setPaint(new GradientPaint(0, 0, tint.darker(), 0, height, tint.brighter()));
            g.fillRect(0, 0, width, height);
            drawTestBars(g, width, height);

            // 字号跟着分辨率走：176×144（QCIF）到 1024×768 差了五倍，固定字号在小图上会糊成一团。
            int base = Math.max(10, height / 12);
            int margin = Math.max(6, height / 24);

            drawText(g, plateNo, margin, margin + base, base, Font.BOLD);
            drawText(g, "CH" + channel + "   #" + index + "/" + total,
                    margin, margin + base * 2 + base / 4, (int) (base * 0.7), Font.PLAIN);
            drawText(g, OffsetDateTime.now(TerminalTime.ZONE).format(STAMP),
                    margin, height - margin, (int) (base * 0.7), Font.PLAIN);
            drawText(g, "SIMULATED", margin, margin + base * 3, (int) (base * 0.55), Font.PLAIN);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", buffer)) {
            throw new IOException("当前 JRE 没有可用的 JPEG 编码器，无法合成照片");
        }
        return buffer.toByteArray();
    }

    /**
     * 右下角画一组色条，让人一眼看出这是合成图而不是真实画面。
     *
     * <p>宽度取 {@code width/16} 而不是 {@code width/8}：八条按 1/8 排开正好铺满整幅宽度，
     * 会把左下角的时间戳压在花花绿绿的色条上读不出来。只占右半边就互不打扰。
     */
    private static void drawTestBars(Graphics2D g, int width, int height) {
        int barHeight = Math.max(4, height / 10);
        int barWidth = Math.max(3, width / 16);
        int top = height - barHeight - Math.max(2, height / 40);
        Color[] bars = {
            Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN,
            Color.MAGENTA, Color.RED, Color.BLUE, Color.BLACK,
        };
        for (int i = 0; i < bars.length; i++) {
            g.setColor(bars[i]);
            g.fillRect(width - barWidth * (bars.length - i), top, barWidth, barHeight);
        }
    }

    /**
     * 带描边的文字。
     *
     * <p>描边不是装饰：底色是渐变的，纯白字落在浅色区域上就看不见了，而这些字正是验收时要读的。
     */
    private static void drawText(Graphics2D g, String text, int x, int y, int size, int style) {
        g.setFont(new Font(Font.SANS_SERIF, style, size));
        g.setColor(new Color(0, 0, 0, 160));
        g.drawString(text, x + 1, y + 1);
        g.setColor(Color.WHITE);
        g.drawString(text, x, y);
    }
}
