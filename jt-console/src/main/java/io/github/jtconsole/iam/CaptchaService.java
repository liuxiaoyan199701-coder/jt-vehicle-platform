package io.github.jtconsole.iam;

import io.github.jtconsole.config.ConsoleProperties;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 自生成图形验证码。
 *
 * <p>选它而不是短信/邮箱验证码，是因为后者要引入外部服务商，与「默认零外部依赖启动」相悖；
 * 而注册入口真正的把关是人工审批，验证码只需要挡住批量脚本。
 *
 * <p>验证码存内存并有容量上限——这是个公网可达的入口，无上限的缓存等于把内存交给外人支配。
 */
@Service
public class CaptchaService {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 44;

    private final ConsoleProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    @Autowired
    public CaptchaService(ConsoleProperties properties) {
        this(properties, Clock.systemUTC());
    }

    CaptchaService(ConsoleProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Challenge issue() {
        purgeExpired();
        if (challenges.size() >= properties.getRegistration().getCaptchaMaxEntries()) {
            // 容量已满时不再签发，而不是驱逐旧条目：驱逐会让攻击者用洪水冲掉正常用户的验证码。
            throw IamException.invalid("验证码服务繁忙，请稍后再试");
        }
        String token = UUID.randomUUID().toString();
        StringBuilder code = new StringBuilder(LENGTH);
        for (int index = 0; index < LENGTH; index++) {
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        String text = code.toString();
        Instant expiresAt = clock.instant().plus(properties.getRegistration().getCaptchaTtl());
        challenges.put(token, new Challenge(token, text, expiresAt, render(text)));
        return challenges.get(token);
    }

    /** 校验并作废：验证码一次性使用，重放同一组 token/code 会失败。 */
    public boolean verifyAndConsume(String token, String answer) {
        purgeExpired();
        if (token == null || answer == null) {
            return false;
        }
        Challenge challenge = challenges.remove(token);
        if (challenge == null || !clock.instant().isBefore(challenge.expiresAt())) {
            return false;
        }
        return challenge.text().equalsIgnoreCase(answer.trim());
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        Iterator<Map.Entry<String, Challenge>> entries = challenges.entrySet().iterator();
        while (entries.hasNext()) {
            if (!now.isBefore(entries.next().getValue().expiresAt())) {
                entries.remove();
            }
        }
    }

    private String render(String text) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(0xF2, 0xF4, 0xF8));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            for (int line = 0; line < 6; line++) {
                graphics.setColor(new Color(
                        160 + random.nextInt(60), 160 + random.nextInt(60), 160 + random.nextInt(60)));
                graphics.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            for (int index = 0; index < text.length(); index++) {
                graphics.setColor(new Color(
                        30 + random.nextInt(90), 30 + random.nextInt(90), 60 + random.nextInt(120)));
                graphics.drawString(String.valueOf(text.charAt(index)),
                        14 + index * 26, 32 + random.nextInt(6) - 3);
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", buffer);
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(buffer.toByteArray());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** {@code imageDataUrl} 可直接放进 {@code <img src>}；{@code text} 不对外返回。 */
    public record Challenge(String token, String text, Instant expiresAt, String imageDataUrl) {}
}
