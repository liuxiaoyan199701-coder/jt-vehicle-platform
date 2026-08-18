package io.github.jtconsole.ai.briefing;

import io.github.jtconsole.config.ConsoleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时生成看板要点。
 *
 * <p><b>为什么预生成而不是打开首页即生成</b>：首页会被反复打开、多人打开。每次都调模型，
 * 用户要对着转圈等几秒，十个人一天开五次就是五十次模型调用，而且同一租户的不同人会看到
 * 措辞不同的「今日要点」——那会直接削弱这块看板的可信度：同一件事，两个人说的不一样，
 * 就没人敢照着它排优先级了。
 *
 * <p>代价是最多滞后一个生成周期，由首页的「重新分析」按钮兜底。
 */
@Component
@ConditionalOnProperty(prefix = "jt.console.ai.briefing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BriefingScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BriefingScheduler.class);

    private final BriefingService briefings;

    public BriefingScheduler(BriefingService briefings, ConsoleProperties properties) {
        this.briefings = briefings;
        LOGGER.info("看板要点定时生成已启用：{}", properties.getAi().getBriefing().getCron());
    }

    /**
     * 逐租户串行生成。
     *
     * <p>串行而不是并发：模型调用是这里最慢的一步，并发起来会同时占满线程池，
     * 而对话入口和它抢的是同一批资源——用户正在聊天时后台跑简报，聊天会被拖慢。
     * 简报晚几十秒没人在意，对话卡一下立刻就有人发现。
     */
    @Scheduled(cron = "${jt.console.ai.briefing.cron:0 7 * * * *}")
    public void generate() {
        long started = System.currentTimeMillis();
        briefings.generateAll();
        LOGGER.debug("看板要点生成完毕，耗时 {}ms", System.currentTimeMillis() - started);
    }

    /** 清理过期要点。低频即可，与生成错开。 */
    @Scheduled(cron = "${jt.console.ai.briefing.cleanup-cron:0 33 5 * * *}")
    public void purge() {
        int removed = briefings.purgeExpired();
        if (removed > 0) {
            LOGGER.info("清理了 {} 条过期看板要点", removed);
        }
    }
}
