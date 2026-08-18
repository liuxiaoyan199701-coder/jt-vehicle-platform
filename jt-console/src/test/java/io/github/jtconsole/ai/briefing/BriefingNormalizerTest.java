package io.github.jtconsole.ai.briefing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 简报的防幻觉闸门。
 *
 * <p><b>为什么这组断言比功能本身重要</b>：模型编一个数字出来，看板上会显示得和真数据一模一样，
 * 没有任何视觉差别。运营人员据此派人去查一台其实没事的车，或者忽略一条真实告警——
 * 而没有人会怀疑那个数字。这道闸失效时同样没有现象，只有断言拦得住。
 */
class BriefingNormalizerTest {

    private static DashboardFinding finding(String id, DashboardFinding.Severity severity) {
        return new DashboardFinding(
                id,
                DashboardFinding.Category.OFFLINE,
                severity,
                "粤B12345 已连续 8 小时没有上报",
                Map.of("离线时长(小时)", 8),
                List.of("138000000000"),
                new DashboardFinding.Link("track", Map.of("device", "138000000000"), "查看轨迹"));
    }

    @Test
    void keepsItemsThatReferenceARealFinding() {
        List<DashboardFinding> candidates = List.of(finding("offline-1", DashboardFinding.Severity.WARN));
        BriefingSpec spec = new BriefingSpec(List.of(
                new BriefingSpec.Item("offline-1", "粤B12345 从昨晚起就没信号了，建议联系司机")));

        List<BriefingItem> items = BriefingNormalizer.normalize(spec, candidates, 5);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().text()).contains("建议联系司机");
        // 数字、严重度、链接全部来自候选，不来自模型。
        assertThat(items.getFirst().facts()).containsEntry("离线时长(小时)", 8);
        assertThat(items.getFirst().severity()).isEqualTo(DashboardFinding.Severity.WARN);
        assertThat(items.getFirst().deviceIds()).containsExactly("138000000000");
    }

    /** 核心断言：模型编造的 id 必须整条丢弃，不能「保留但存疑」。 */
    @Test
    void dropsItemsThatReferenceAnInventedFinding() {
        List<DashboardFinding> candidates = List.of(finding("offline-1", DashboardFinding.Severity.WARN));
        BriefingSpec spec = new BriefingSpec(List.of(
                new BriefingSpec.Item("offline-1", "真实的一条"),
                new BriefingSpec.Item("alarm-surge-999", "今日告警上涨 300%，情况紧急")));

        List<BriefingItem> items = BriefingNormalizer.normalize(spec, candidates, 5);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().text()).isEqualTo("真实的一条");
        assertThat(items).noneMatch(i -> i.text().contains("300%"));
    }

    /** 模型改不了严重度——否则同一件事今天严重明天一般，就没法据此排优先级。 */
    @Test
    void severityAlwaysComesFromTheFinding() {
        List<DashboardFinding> candidates =
                List.of(finding("offline-1", DashboardFinding.Severity.CRITICAL));
        BriefingSpec spec = new BriefingSpec(List.of(
                new BriefingSpec.Item("offline-1", "小问题，不用管")));

        List<BriefingItem> items = BriefingNormalizer.normalize(spec, candidates, 5);

        assertThat(items.getFirst().severity()).isEqualTo(DashboardFinding.Severity.CRITICAL);
    }

    @Test
    void deduplicatesRepeatedReferences() {
        List<DashboardFinding> candidates = List.of(finding("offline-1", DashboardFinding.Severity.WARN));
        BriefingSpec spec = new BriefingSpec(List.of(
                new BriefingSpec.Item("offline-1", "第一次说"),
                new BriefingSpec.Item("offline-1", "换个说法再说一次")));

        List<BriefingItem> items = BriefingNormalizer.normalize(spec, candidates, 5);

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().text()).isEqualTo("第一次说");
    }

    @Test
    void honoursTheItemLimit() {
        List<DashboardFinding> candidates = List.of(
                finding("a", DashboardFinding.Severity.WARN),
                finding("b", DashboardFinding.Severity.WARN),
                finding("c", DashboardFinding.Severity.WARN));
        BriefingSpec spec = new BriefingSpec(List.of(
                new BriefingSpec.Item("a", "一"),
                new BriefingSpec.Item("b", "二"),
                new BriefingSpec.Item("c", "三")));

        assertThat(BriefingNormalizer.normalize(spec, candidates, 2)).hasSize(2);
    }

    /** 文本为空时退回候选自带的陈述——那句话是代码写的，可信。 */
    @Test
    void fallsBackToTheFindingSummaryWhenTextIsBlank() {
        List<DashboardFinding> candidates = List.of(finding("offline-1", DashboardFinding.Severity.WARN));
        BriefingSpec spec = new BriefingSpec(List.of(new BriefingSpec.Item("offline-1", "   ")));

        List<BriefingItem> items = BriefingNormalizer.normalize(spec, candidates, 5);

        assertThat(items.getFirst().text()).isEqualTo("粤B12345 已连续 8 小时没有上报");
    }

    @Test
    void truncatesOverlongText() {
        List<DashboardFinding> candidates = List.of(finding("offline-1", DashboardFinding.Severity.WARN));
        BriefingSpec spec = new BriefingSpec(List.of(
                new BriefingSpec.Item("offline-1", "很".repeat(300))));

        List<BriefingItem> items = BriefingNormalizer.normalize(spec, candidates, 5);

        assertThat(items.getFirst().text()).hasSizeLessThan(140);
        assertThat(items.getFirst().text()).endsWith("…");
    }

    /**
     * 模型不可用时的兜底：措辞机械，但每个字都是代码写的。
     *
     * <p>比一片空白强得多——数据本来就在，只是没人用人话说一遍。
     */
    @Test
    void fallbackUsesFindingSummariesOrderedBySeverity() {
        List<DashboardFinding> candidates = List.of(
                finding("info", DashboardFinding.Severity.INFO),
                finding("critical", DashboardFinding.Severity.CRITICAL),
                finding("warn", DashboardFinding.Severity.WARN));

        List<BriefingItem> items = BriefingNormalizer.fallback(candidates, 2);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).severity()).isEqualTo(DashboardFinding.Severity.CRITICAL);
        assertThat(items.get(1).severity()).isEqualTo(DashboardFinding.Severity.WARN);
    }

    @Test
    void returnsNothingWhenThereAreNoCandidates() {
        BriefingSpec spec = new BriefingSpec(List.of(new BriefingSpec.Item("x", "凭空一条")));

        assertThat(BriefingNormalizer.normalize(spec, List.of(), 5)).isEmpty();
    }
}
