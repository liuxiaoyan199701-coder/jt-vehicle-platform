package io.github.jtconsole.ai.agent;

import io.github.jtconsole.config.ConsoleProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 用带轮数上限的实现替换框架默认的工具调用管理器。
 *
 * <p>只在装配出了模型时才生效（{@code @ConditionalOnBean(ChatModel.class)}）——AI 功能关闭时
 * 整条链路都不存在，凭空多一个 bean 只会让「有没有启用」这件事更难判断。
 *
 * <p>标 {@code @Primary} 而不是同名覆盖：Spring AI 的自动配置以
 * {@code @ConditionalOnMissingBean} 提供默认实现，同名会让它直接不装配，而我们需要它作为被装饰的
 * 委托对象。这里让自动配置照常产出默认实现，再包一层。
 */
@Configuration
@ConditionalOnBean(ChatModel.class)
public class ToolCallingConfig {

    @Bean
    @Primary
    ToolCallingManager boundedToolCallingManager(
            ObjectProvider<ToolCallbackResolver> resolvers,
            ObjectProvider<ToolExecutionExceptionProcessor> processors,
            ObjectProvider<ObservationRegistry> registries,
            ConsoleProperties properties) {
        DefaultToolCallingManager.Builder builder = DefaultToolCallingManager.builder();
        resolvers.ifAvailable(builder::toolCallbackResolver);
        processors.ifAvailable(builder::toolExecutionExceptionProcessor);
        registries.ifAvailable(builder::observationRegistry);
        return new BoundedToolCallingManager(
                builder.build(), properties.getAi().getMaxToolRounds());
    }
}
