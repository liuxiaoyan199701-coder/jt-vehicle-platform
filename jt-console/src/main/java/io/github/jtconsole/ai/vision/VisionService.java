package io.github.jtconsole.ai.vision;

import io.github.jtconsole.config.ConsoleProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 看图员：把图片转成一段文字描述，交给主模型。
 *
 * <p>平台的主模型没有视觉能力。与其为了看图整体换掉它，不如让一个视觉模型专门负责「看」——
 * 它读图、写描述，描述作为普通文本进入主模型的上下文。主模型全程不接触图像，工具调用、
 * 中文运营语境这些既有能力一点不受影响。
 *
 * <p><b>描述即事实来源</b>：主模型看到的是这段文字，不是原图。所以提示词要求描述**只陈述看得见
 * 的内容**，不推断、不脑补——否则一次视觉幻觉会被主模型当作观测事实继续推理下去，而下游再也
 * 无从分辨那句话是看出来的还是编出来的。
 *
 * <p>未配置密钥时本 bean 不装配，调用方通过 {@link VisionUnavailableException} 或 Optional 注入
 * 感知缺失，功能降级而不是报错。
 */
@Service
@ConditionalOnProperty(prefix = "jt.console.ai.vision", name = "api-key")
public class VisionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisionService.class);

    /**
     * 描述用的系统提示。
     *
     * <p>「不确定就说不确定」这一句是本文件里最要紧的约束：车牌、人脸、文字这类内容在低分辨率
     * 抓拍上经常看不清，而模型倾向于给出一个像模像样的答案。一个编出来的车牌号会顺着链路一路
     * 传到运营人员眼前。
     */
    private static final String SYSTEM_PROMPT = """
            你是车辆监控平台的图像识别助手。请用简体中文客观描述图片内容。

            要求：
            1. 只陈述你在图上真正看得见的东西，不要推断意图、不要补全背景故事。
            2. 车牌、路牌、仪表数字等文字内容，看清了就照抄；看不清就明确写「看不清」，
               绝对不要猜一个看起来合理的号码或数字。
            3. 与车辆运营相关的细节优先：车辆、道路、车道、天气、光线、遮挡、异常情况
               （事故、拥堵、施工、货物散落、驾驶员状态等）。
            4. 控制在 200 字以内，不要分点罗列，不要重复图片编号。
            """;

    private final RestClient client;
    private final ConsoleProperties.Ai.Vision config;

    public VisionService(ConsoleProperties properties) {
        this.config = properties.getAi().getVision();
        this.client = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .requestFactory(requestFactory(config.getTimeout()))
                .build();
        LOGGER.info("视觉模型已启用：model={} baseUrl={}", config.getModel(), config.getBaseUrl());
    }

    /**
     * 描述一批图片。
     *
     * @param images      待识别的图片，超出 {@code maxImages} 的部分会被丢弃并在描述中说明
     * @param instruction 额外的观察要求，可为空；用于「重点看有没有人」这类定向提问
     * @return 一段中文描述
     * @throws VisionUnavailableException 上游不可用或返回异常时抛出，由调用方决定降级方式
     */
    public String describe(List<VisionImage> images, String instruction) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("没有可识别的图片");
        }
        int limit = Math.max(1, config.getMaxImages());
        List<VisionImage> accepted = images.size() > limit ? images.subList(0, limit) : images;
        int dropped = images.size() - accepted.size();

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", buildInstruction(accepted, instruction, dropped)));
        for (VisionImage image : accepted) {
            content.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", dataUri(image))));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxOutputTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", content)));

        try {
            Map<?, ?> response = client.post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            String text = extractText(response);
            if (text == null || text.isBlank()) {
                throw new VisionUnavailableException("视觉模型返回了空描述");
            }
            return dropped > 0
                    ? text + "\n（本次只识别了前 " + accepted.size() + " 张，其余 " + dropped + " 张未送检）"
                    : text;
        } catch (RestClientException failure) {
            // 原因要如实透出：把上游 401 说成「识别失败」会让部署方查错方向。
            LOGGER.warn("视觉模型调用失败：{}", failure.getMessage());
            throw new VisionUnavailableException("视觉模型调用失败：" + failure.getMessage(), failure);
        }
    }

    private static String buildInstruction(List<VisionImage> images, String instruction, int dropped) {
        StringBuilder text = new StringBuilder();
        if (images.size() == 1) {
            VisionImage only = images.getFirst();
            text.append("请描述这张图片");
            if (only.label() != null && !only.label().isBlank()) {
                text.append("（").append(only.label()).append("）");
            }
            text.append("。");
        } else {
            text.append("请依次描述这 ").append(images.size()).append(" 张图片");
            text.append("，每张一句，标明是第几张。");
            for (int index = 0; index < images.size(); index++) {
                String label = images.get(index).label();
                if (label != null && !label.isBlank()) {
                    text.append("\n第 ").append(index + 1).append(" 张：").append(label);
                }
            }
        }
        if (instruction != null && !instruction.isBlank()) {
            text.append("\n\n特别关注：").append(instruction.trim());
        }
        if (dropped > 0) {
            text.append("\n\n（调用方还有 ").append(dropped).append(" 张未送检）");
        }
        return text.toString();
    }

    private static String dataUri(VisionImage image) {
        return "data:" + image.mimeType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.bytes());
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<?, ?> response) {
        if (response == null) {
            return null;
        }
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        if (!(list.getFirst() instanceof Map<?, ?> choice)) {
            return null;
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)) {
            return null;
        }
        Object content = message.get("content");
        if (content instanceof String text) {
            return text.trim();
        }
        // 有的兼容实现把 content 也拆成分片数组，取其中的文本片拼起来。
        if (content instanceof List<?> parts) {
            StringBuilder joined = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map && map.get("text") instanceof String text) {
                    joined.append(text);
                }
            }
            return joined.toString().trim();
        }
        return null;
    }

    private static ClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(timeout);
        return factory;
    }
}
