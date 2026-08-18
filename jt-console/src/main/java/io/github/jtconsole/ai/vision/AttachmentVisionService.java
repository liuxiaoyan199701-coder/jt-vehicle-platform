package io.github.jtconsole.ai.vision;

import io.github.jtconsole.config.ConsoleProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把用户随消息发来的图片，变成一段可以直接进主模型上下文的文字。
 *
 * <p><b>界面上不能出现「第二个模型」</b>。所以这一步刻意发生在 agent 循环**开始之前**，
 * 不走工具、不推事件、不产生任何可见的中间态：用户看到的只是自己发出的图，和随后自然流出的
 * 回答。识别耗时体现为回答开始前多等一会儿，与模型「思考」无从区分。
 *
 * <p>注入的文字用第一人称视角写成（「用户发来 2 张图片，我看到……」），让主模型把它当作自己的
 * 观察继续推理，而不是当作一份别人递来的报告去转述。
 */
@Service
public class AttachmentVisionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentVisionService.class);

    private final AttachmentStore store;
    private final Optional<VisionService> vision;
    private final ConsoleProperties.Ai.Vision config;

    public AttachmentVisionService(
            AttachmentStore store,
            Optional<VisionService> vision,
            ConsoleProperties properties) {
        this.store = store;
        this.vision = vision;
        this.config = properties.getAi().getVision();
    }

    public boolean available() {
        return vision.isPresent();
    }

    /**
     * 识别一批附件，返回要拼进用户消息的文字。
     *
     * <p>永不抛异常：识别失败时返回一句说明，让主模型能如实告诉用户「这张图我没看成」，
     * 而不是让整轮对话报错。真实原因（密钥错、超时、上游限流）只进日志，不进对话——
     * 那是运维要看的东西，摆到用户面前只会让人以为平台坏了。
     *
     * @return 要追加到用户消息末尾的文字；无附件或功能未启用时返回空
     */
    public String describe(long accountId, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return "";
        }
        if (vision.isEmpty()) {
            LOGGER.debug("收到 {} 个附件但视觉能力未启用", attachmentIds.size());
            return "\n\n（用户发送了图片，但平台未启用图片识别能力，无法查看图片内容。）";
        }

        int limit = Math.max(1, config.getMaxImages());
        List<VisionImage> images = new ArrayList<>();
        for (String id : attachmentIds) {
            if (images.size() >= limit) {
                break;
            }
            Optional<byte[]> bytes = store.read(accountId, id);
            if (bytes.isEmpty()) {
                LOGGER.debug("附件 {} 不存在或已过期", id);
                continue;
            }
            try {
                // 落盘时已经缩放并转成 JPEG，这里不再重复处理，直接按已知格式包装。
                images.add(new VisionImage(bytes.get(), "image/jpeg", null));
            } catch (RuntimeException malformed) {
                LOGGER.debug("附件 {} 无法作为图片使用：{}", id, malformed.getMessage());
            }
        }

        if (images.isEmpty()) {
            return "\n\n（用户发送的图片已失效或无法读取，无法查看图片内容。）";
        }

        try {
            String description = vision.get().describe(images, null);
            String header = images.size() == 1
                    ? "\n\n（用户随消息发来一张图片，我看到的内容是：\n"
                    : "\n\n（用户随消息发来 " + images.size() + " 张图片，我看到的内容是：\n";
            return header + description + "\n）";
        } catch (RuntimeException failure) {
            LOGGER.warn("附件识别失败（账号 {}）：{}", accountId, failure.getMessage());
            return "\n\n（用户发送了图片，但图片识别服务暂时不可用，我这次没能看到图片内容。）";
        }
    }
}
