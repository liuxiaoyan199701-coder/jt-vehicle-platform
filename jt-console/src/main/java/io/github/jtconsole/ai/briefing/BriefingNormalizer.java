package io.github.jtconsole.ai.briefing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把模型产出的简报校验成可信内容。
 *
 * <p><b>这里是整套设计里最要紧的一道闸</b>：模型只被允许挑选与转述，任何引用不到候选发现的
 * 要点一律丢弃。丢弃而不是「保留但标注存疑」——看板上的数字要么可追溯到一次真实查询，
 * 要么不该出现。一条看起来合理、实则无出处的结论，比没有结论危险得多。
 *
 * <p>宽进严出：措辞、顺序、条数这些无歧义的问题静默修正；引用错误、文本为空这些有歧义的
 * 直接拒收。
 */
public final class BriefingNormalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BriefingNormalizer.class);

    /** 单条要点的字数上限。超了就截断——一屏看板放不下长段落，模型偶尔会写成小作文。 */
    private static final int MAX_TEXT_CHARS = 120;

    private BriefingNormalizer() {
    }

    /**
     * 校验并组装最终要点。
     *
     * @param spec       模型产出
     * @param candidates 本轮的候选发现
     * @param limit      最多保留几条
     * @return 每条都能追溯到一个候选发现的要点列表
     */
    public static List<BriefingItem> normalize(
            BriefingSpec spec, List<DashboardFinding> candidates, int limit) {
        if (spec == null || spec.items().isEmpty() || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, DashboardFinding> byId = candidates.stream()
                .collect(Collectors.toMap(DashboardFinding::id, Function.identity(), (a, b) -> a));

        List<BriefingItem> result = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (BriefingSpec.Item item : spec.items()) {
            if (result.size() >= limit) {
                break;
            }
            DashboardFinding finding = byId.get(item.findingId());
            if (finding == null) {
                // 模型编了一个不存在的 id。这正是这道闸要挡的东西。
                LOGGER.warn("简报要点引用了不存在的候选 id：{}，已丢弃", item.findingId());
                continue;
            }
            // 同一条候选被引用两次，说明模型在重复表达，留第一条。
            if (!used.add(finding.id())) {
                continue;
            }
            String text = item.text() == null ? "" : item.text().strip();
            if (text.isBlank()) {
                // 文本为空时退回候选自带的事实陈述——那句话本来就是代码写的，可信。
                text = finding.summary();
            }
            if (text.length() > MAX_TEXT_CHARS) {
                text = text.substring(0, MAX_TEXT_CHARS) + "…";
            }
            result.add(new BriefingItem(
                    finding.id(),
                    finding.category(),
                    // 严重度取自候选，不取模型：同一件事今天说严重明天说一般，
                    // 就没法据此排优先级，而排优先级正是这块看板的意义。
                    finding.severity(),
                    text,
                    finding.facts(),
                    finding.deviceIds(),
                    finding.link()));
        }
        return result;
    }

    /**
     * 模型完全不可用时的兜底。
     *
     * <p>直接用候选发现自带的事实陈述，按严重度排序。措辞机械，但每个字都是代码写的——
     * 简报的价值下降，可信度不变。这比「AI 暂时不可用」一片空白强得多：
     * 数据本来就在，只是没人用人话说一遍。
     */
    public static List<BriefingItem> fallback(List<DashboardFinding> candidates, int limit) {
        return candidates.stream()
                .sorted((a, b) -> b.severity().compareTo(a.severity()))
                .limit(limit)
                .map(f -> new BriefingItem(
                        f.id(), f.category(), f.severity(), f.summary(),
                        f.facts(), f.deviceIds(), f.link()))
                .toList();
    }
}
