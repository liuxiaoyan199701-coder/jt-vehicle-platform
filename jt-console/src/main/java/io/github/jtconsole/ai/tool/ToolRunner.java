package io.github.jtconsole.ai.tool;

import io.github.jtconsole.ai.agent.AiEvent;
import io.github.jtconsole.config.ConsoleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 工具执行的公共外壳：推送开始与结束事件、序列化结果、把异常转成结构化结果。
 *
 * <p>异常**不重新抛出**是有意的：一次工具失败不该中断整个对话。把失败原因作为工具结果交回模型，
 * 它可以换个参数重试，或据此向用户说明情况——这比让用户看到一个「服务器错误」有用得多。
 */
@Component
public class ToolRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolRunner.class);

    private final ObjectMapper objectMapper;
    private final ConsoleProperties properties;

    public ToolRunner(ObjectMapper objectMapper, ConsoleProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String run(ToolSession session, String name, String brief, ToolBody body) {
        session.events().emit(AiEvent.toolStart(name, brief));
        try {
            String json = write(body.get());
            session.events().emit(AiEvent.toolEnd(name, true, brief));
            return json;
        } catch (RuntimeException failure) {
            LOGGER.warn("AI 工具 {} 执行失败", name, failure);
            session.events().emit(AiEvent.toolEnd(name, false, brief + "（失败）"));
            return write(ToolResults.error(describe(failure)));
        }
    }

    public String write(Object value) {
        return ToolResults.write(objectMapper, value, properties.getAi().getToolResultCharLimit());
    }

    /**
     * 业务异常的文案（「车辆不存在」这类）对模型有用，直接透出；其余异常只给通用说明，
     * 避免把内部细节写进模型上下文。
     */
    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank() || message.length() > 120) {
            return "查询失败";
        }
        return message;
    }

    @FunctionalInterface
    public interface ToolBody {
        Object get();
    }
}
