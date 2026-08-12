package io.github.jtplatform.media.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtplatform.media.talkback.TalkbackMode;
import io.github.jtplatform.media.talkback.TalkbackProperties;
import io.github.jtplatform.media.talkback.TalkbackService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TalkbackAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MediaAutoConfiguration.class)
            .withPropertyValues("jt.media.server.enabled=false");

    @Test
    void defaultsToExclusiveAndBindsMixModeWithItsFixedInterval() {
        contextRunner.run(context -> {
            TalkbackProperties defaults = context.getBean(TalkbackProperties.class);
            assertThat(defaults.getMode()).isEqualTo(TalkbackMode.EXCLUSIVE);
            assertThat(defaults.getMixInterval()).isEqualTo(Duration.ofMillis(20));
            assertThat(context.getBean(TalkbackService.class).mode()).isEqualTo(TalkbackMode.EXCLUSIVE);
        });

        contextRunner
                .withPropertyValues(
                        "jt.media.talkback.mode=mix",
                        "jt.media.talkback.mix-interval=35ms")
                .run(context -> {
                    TalkbackProperties configured = context.getBean(TalkbackProperties.class);
                    assertThat(configured.getMode()).isEqualTo(TalkbackMode.MIX);
                    assertThat(configured.getMixInterval()).isEqualTo(Duration.ofMillis(35));
                    assertThat(context.getBean(TalkbackService.class).mode()).isEqualTo(TalkbackMode.MIX);
                });
    }

    @Test
    void rejectsNonPositiveMixIntervals() {
        contextRunner
                .withPropertyValues("jt.media.talkback.mix-interval=0ms")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("jt.media.talkback.mix-interval must be positive");
                });
    }
}
