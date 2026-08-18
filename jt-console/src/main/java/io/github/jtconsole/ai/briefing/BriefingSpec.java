package io.github.jtconsole.ai.briefing;

import java.util.List;
import java.util.Objects;

/**
 * 模型产出的简报结构。
 *
 * <p><b>模型只能写两样东西</b>：挑哪几条候选发现（{@code findingId}），以及怎么把它说成人话
 * （{@code text}）。数字、严重度、涉及车辆、导航目标全部来自候选发现本身，模型碰不到。
 *
 * <p>所以这个结构里**刻意没有**数值字段——不是忘了，是不能有。一旦允许模型自带数字，
 * 简报里就会出现无法追溯到任何一次查询的数据，而看板上的数字一旦不可信，整块就没用了。
 */
public record BriefingSpec(List<Item> items) {

    public BriefingSpec {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * @param findingId 必须是某条候选发现的 id。对不上的整条丢弃
     * @param text      一句话，运营口吻。可以合并多条同类发现的表述，但不能引入新数字
     */
    public record Item(String findingId, String text) {
        public Item {
            Objects.requireNonNull(findingId, "findingId");
            Objects.requireNonNull(text, "text");
        }
    }
}
