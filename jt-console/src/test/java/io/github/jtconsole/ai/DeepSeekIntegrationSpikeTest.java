package io.github.jtconsole.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 对真实 DeepSeek 服务的接入验证：确认 Spring AI 的工具调用、工具上下文与流式响应在本仓的
 * Spring Boot 4 / Jackson 3 组合下确实可用。
 *
 * <p>默认跳过——它要花钱、要联网，不该进常规回归。需要时设置 {@code JT_AI_API_KEY} 再跑：
 * {@code mvn test -Dtest=DeepSeekIntegrationSpikeTest}。
 *
 * <p>之所以在动手写十几个业务工具之前先验这一遍：如果框架的工具上下文传不进来，整个
 * 「数据范围不经模型传递」的安全设计就得换一种实现方式，越早发现越好。
 */
@SpringBootTest(classes = DeepSeekIntegrationSpikeTest.SpikeConfiguration.class)
@TestPropertySource(properties = {
        "spring.ai.model.chat=deepseek",
        "spring.ai.deepseek.chat.options.model=deepseek-chat",
        "spring.ai.deepseek.chat.options.temperature=0"
})
@EnabledIfEnvironmentVariable(named = "JT_AI_API_KEY", matches = ".+")
class DeepSeekIntegrationSpikeTest {

    /** 工具从上下文里取出的值，用来证明上下文确实穿透到了工具方法。 */
    private static final AtomicReference<Object> SEEN_SCOPE = new AtomicReference<>();

    @Autowired
    private ChatModel chatModel;

    @Test
    void theModelCallsAToolAndTheToolSeesTheContextWeSuppliedNotAnythingTheModelSaid() {
        SEEN_SCOPE.set(null);
        ChatClient client = ChatClient.builder(chatModel).build();

        String answer = client.prompt()
                .system("你是车辆管理平台的助手。回答车辆数量问题时必须调用工具，不要凭空作答。")
                .user("平台上现在有多少台车？只回答数字。")
                .toolCallbacks(List.of(ToolCallbacks.from(new CountingTool())))
                .toolContext(Map.of("dataScope", "tenant-42"))
                .call()
                .content();

        // 工具真的被调用了，而且拿到的是我们放进上下文的值——模型无从影响它。
        assertThat(SEEN_SCOPE.get()).isEqualTo("tenant-42");
        assertThat(answer).contains("7");
    }

    @Test
    void streamingYieldsIncrementalChunksRatherThanOneBlob() {
        ChatClient client = ChatClient.builder(chatModel).build();

        List<String> chunks = client.prompt()
                .user("用一句话说明什么是车辆里程统计，不要少于三十个字。")
                .stream()
                .content()
                .collectList()
                .block();

        assertThat(chunks).isNotNull();
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(String.join("", chunks)).isNotBlank();
    }

    /** 只为验证机制而存在的假工具。 */
    static class CountingTool {

        @Tool(name = "count_vehicles", description = "查询当前平台上的车辆总数")
        String countVehicles(
                @ToolParam(description = "是否只统计在线车辆", required = false) Boolean onlineOnly,
                ToolContext context) {
            SEEN_SCOPE.set(context.getContext().get("dataScope"));
            return "{\"total\":7}";
        }
    }

    @org.springframework.boot.autoconfigure.SpringBootApplication
    static class SpikeConfiguration {
    }
}
