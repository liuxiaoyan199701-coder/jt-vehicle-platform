package io.github.jtconsole.ai.briefing;

import io.github.jtconsole.ai.vision.SnapshotVisionService;
import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.MediaFile;
import io.github.jtconsole.repository.MediaRepository;
import io.github.jtconsole.security.DataScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 生成一份看板要点。
 *
 * <p><b>单次调用，不挂任何工具。</b>服务端已经知道该给模型哪些数据——候选发现是代码算好的，
 * 视觉巡检结果也是先跑完再喂进去的。模型不需要去「查」，因此也不需要工具，
 * 于是没有循环，成本与时延都可预测。这与刚做的 {@code ToolRoundBudget} 是同一类考虑，
 * 只是这里从源头上就不给它循环的机会。
 *
 * <p>模型的职责只有两件：从候选里挑几条、把它说成运营听得懂的话。数字、严重度、涉及车辆
 * 全部由 {@link BriefingNormalizer} 从候选发现回填，模型碰不到。
 */
@Service
public class BriefingGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BriefingGenerator.class);

    /**
     * 视觉巡检的观察指令。
     *
     * <p>问「有没有异常」而不是「描述画面」：描述会得到一段散文，还要再解析一次；
     * 而巡检要的是一个判断。同时明确「正常就说正常」——不给这句的话，模型倾向于
     * 从每张图里找出点什么来说，于是每天都有「异常」。
     */
    private static final String INSPECTION = """
            这些是车载摄像头最近拍的照片。请判断画面是否异常：全黑、严重遮挡、严重失焦、镜头污损。
            每张一句话，正常就直接说「正常」。不要描述画面细节，不要猜测拍摄内容。""";

    private static final String SYSTEM_PROMPT = """
            你是车队运营看板的值班助手。下面给你一份**已经由系统查询得出的**候选发现清单，
            每条带一个 id。

            你的任务：
            1. 从中挑出今天最值得运营人员关注的几条，按重要性排序。
            2. 用简短的运营口吻改写措辞，一句话说清「发生了什么、建议怎么做」。
            3. 可以把同类的多条合并成一句表述，此时引用其中最有代表性的那条 id。

            硬性要求：
            - **只能引用清单里出现过的 id**，绝对不要编造 id。
            - **不要引入清单里没有的数字**。你可以复述清单里的数字，但不能计算新的、
              也不能凭印象补充。
            - 不要说「一切正常」这类没有信息量的话。没什么值得说的就少说几条，或者一条不说。
            - 每条控制在 60 字以内。

            只返回 JSON，形如：
            {"items":[{"findingId":"offline-1","text":"粤B12345 已 8 小时无上报，建议联系司机确认"}]}
            """;

    private final ObjectProvider<ChatModel> chatModel;
    private final MediaRepository media;
    private final SnapshotVisionService snapshotVision;
    private final ObjectMapper objectMapper;
    private final ConsoleProperties properties;

    public BriefingGenerator(
            ObjectProvider<ChatModel> chatModel,
            MediaRepository media,
            SnapshotVisionService snapshotVision,
            ObjectMapper objectMapper,
            ConsoleProperties properties) {
        this.chatModel = chatModel;
        this.media = media;
        this.snapshotVision = snapshotVision;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public boolean available() {
        return chatModel.getIfAvailable() != null;
    }

    /**
     * 组装候选、跑模型、产出要点。
     *
     * @param candidates 代码算出的候选发现
     * @param scope      数据范围，用于抽检抓拍
     * @return 要点列表；模型不可用或产出不可信时退回代码生成的兜底版本
     */
    public Outcome generate(List<DashboardFinding> candidates, DataScope scope) {
        long started = System.nanoTime();
        List<DashboardFinding> all = new ArrayList<>(candidates);
        cameraFinding(scope).ifPresent(all::add);

        int limit = properties.getAi().getBriefing().getMaxItems();
        if (all.isEmpty()) {
            // 没有任何发现是正常且常见的结果，不必为此调一次模型。
            return new Outcome(List.of(), "", elapsedMs(started), null);
        }

        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            return new Outcome(
                    BriefingNormalizer.fallback(all, limit), "", elapsedMs(started), null);
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of("findings", asPayload(all)));
            String reply = ChatClient.builder(model).build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(payload)
                    // 刻意不挂 toolCallbacks：无工具即无循环。
                    .call()
                    .content();
            BriefingSpec spec = parse(reply);
            List<BriefingItem> items = BriefingNormalizer.normalize(spec, all, limit);
            if (items.isEmpty()) {
                // 模型回了东西但一条都没通过校验（多半是编了 id）。退回兜底而不是给空白：
                // 数据本来就在，只是没人用人话说一遍。
                LOGGER.warn("简报要点全部未通过校验，退回代码兜底");
                items = BriefingNormalizer.fallback(all, limit);
            }
            return new Outcome(items, model.toString(), elapsedMs(started), null);
        } catch (RuntimeException failure) {
            LOGGER.warn("简报生成失败，退回代码兜底：{}", failure.getMessage());
            return new Outcome(
                    BriefingNormalizer.fallback(all, limit), "", elapsedMs(started),
                    failure.getMessage());
        }
    }

    /**
     * 视觉巡检：抽检最近的抓拍，看有没有摄像头异常。
     *
     * <p>这是纯数据看板做不到的一类发现——「摄像头被遮住了」不是数据，是画面。
     *
     * <p>识别不可用时返回空：巡检是加分项，缺了它简报照常出。
     */
    private java.util.Optional<DashboardFinding> cameraFinding(DataScope scope) {
        if (!snapshotVision.available() || !properties.getAi().getBriefing().isInspectCameras()) {
            return java.util.Optional.empty();
        }
        int sample = Math.max(1, properties.getAi().getVision().getMaxImages());
        MediaRepository.MediaFilter filter = new MediaRepository.MediaFilter(
                null, null, null, null, null, null, null, 1, sample);
        List<MediaFile> photos = media.search(filter, scope).items();
        if (photos.isEmpty()) {
            return java.util.Optional.empty();
        }
        SnapshotVisionService.Described described = snapshotVision.describe(photos, INSPECTION);
        if (described.isEmpty()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("抽检张数", described.coveredTimes().size());
        facts.put("巡检结论", described.text());
        return java.util.Optional.of(new DashboardFinding(
                "camera-inspection",
                DashboardFinding.Category.CAMERA,
                // 严重度保守取 INFO：判断来自模型看图，不如阈值计算确定。
                // 真有问题时模型会在文字里说清楚，由人决定要不要去看。
                DashboardFinding.Severity.INFO,
                "抽检了 %d 张最近的抓拍".formatted(described.coveredTimes().size()),
                facts,
                photos.stream().map(MediaFile::deviceId).distinct().toList(),
                new DashboardFinding.Link("media", Map.of(), "查看抓拍")));
    }

    /** 只把模型需要的字段给它：id、类别、严重度、事实陈述。设备号与链接它用不上。 */
    private static List<Map<String, Object>> asPayload(List<DashboardFinding> findings) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DashboardFinding finding : findings) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", finding.id());
            row.put("类别", finding.category().name());
            row.put("严重度", finding.severity().name());
            row.put("事实", finding.summary());
            if (!finding.facts().isEmpty()) {
                row.put("数据", finding.facts());
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 解析模型回复。
     *
     * <p>容忍围栏（{@code ```json}）：模型经常这么回，为此重试一轮不值得。
     * 解析失败抛出，由调用方退回兜底。
     */
    private BriefingSpec parse(String reply) {
        if (reply == null || reply.isBlank()) {
            throw new IllegalStateException("模型返回空内容");
        }
        String text = reply.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型回复里没有 JSON 对象");
        }
        return objectMapper.readValue(text.substring(start, end + 1), BriefingSpec.class);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /**
     * @param model 实际使用的模型标识，兜底时为空串
     * @param error 生成失败的原因，成功时为 null。**失败也要落库**——一块空白的看板分不清是
     *              「今天没事」还是「生成挂了」，而这两者的处理方式完全相反
     */
    public record Outcome(List<BriefingItem> items, String model, long elapsedMs, String error) {}
}
