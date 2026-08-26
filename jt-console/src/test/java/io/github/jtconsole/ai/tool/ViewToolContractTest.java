package io.github.jtconsole.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 视图展示后回给模型的那句话，必须说清**方位**。
 *
 * <p>页面把一条回复固定渲染成「文字 → 视图 → 动作卡」，视图在文字**下方**；
 * 而模型自己的视角是「先调工具、后说话」，不告诉它方位它就会脑补成「上方」——
 * 线上真的出现过「轨迹地图已在上方展示」，而图在下面。
 *
 * <p>动作卡那边早在系统提示里写明了「紧跟在你这条消息下方」，视图这边一直漏着同样的一句。
 * 这类「写了但消费方拿不到 / 压根没写」的契约缺口在本项目栽过几次，
 * 所以照例用断言钉住**最终产物**，而不是只测生成它的方法。
 */
class ViewToolContractTest {

    @Test
    void theReplyTellsTheModelTheViewSitsBelowItsMessage() {
        Map<String, Object> reply = shownReply();

        assertThat(reply).containsEntry("status", "shown");
        String note = String.valueOf(reply.get("note"));
        assertThat(note).contains("下方");
        // 光说「在下方」还不够：模型仍可能顺口写成「上方」，所以把这条禁令也一并交代。
        assertThat(note).contains("不要说「上方」");
    }

    /** 末句同样是契约：不写的话模型会把图上的数值再用文字念一遍，图就白做了。 */
    @Test
    void theReplyStillForbidsReadingTheChartBackOutLoud() {
        String note = String.valueOf(shownReply().get("note"));

        assertThat(note).contains("不要逐条复述");
        // 不回传编号：给了它就会在回答里复述，复述几次之后就学会凭空编一个说「已展示」。
        assertThat(shownReply()).doesNotContainKeys("viewId", "id", "params");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shownReply() {
        try {
            Method method = ViewTools.class.getDeclaredMethod("shownReply");
            method.setAccessible(true);
            return (Map<String, Object>) method.invoke(null);
        } catch (ReflectiveOperationException unreachable) {
            throw new AssertionError("ViewTools 不再有 shownReply()，视图回包的契约需要重新钉住",
                    unreachable);
        }
    }
}
