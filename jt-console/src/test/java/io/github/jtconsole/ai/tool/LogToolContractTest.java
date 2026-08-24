package io.github.jtconsole.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 参数契约必须真的送达模型。
 *
 * <p>Spring AI 由 {@code @Tool}/{@code @ToolParam} 注解反射生成 schema——写在注解里的说明模型看得见，
 * 写在 javadoc、README 或提示词以外任何地方的都看不见。围栏那次的教训是：清单只报了动作名，
 * 模型只能猜字段名和格式，猜错了才在被拒时收到清单，一个请求为此往返六轮。
 *
 * <p>本测试钉住的是「描述里有没有格式说明与示例」，不是文案本身——文案可以改，
 * 但一旦有人把「0x0200 与 512 等价」这类话删掉，这里必须红。
 */
class LogToolContractTest {

    @Test
    void theQueryToolTellsTheModelEveryParameterNameFormatAndExample() {
        Method method = toolMethod("query_device_logs");
        String description = method.getAnnotation(Tool.class).description();

        // 工具本身要说清「返回什么」与「怎么拿正文」，否则模型会以为摘要行就是全部。
        assertThat(description).contains("UP").contains("DOWN").contains("CONNECTION");
        assertThat(description).contains("get_device_log_detail");
        assertThat(description).contains("只读");

        Map<String, String> params = toolParams(method);
        assertThat(params).containsOnlyKeys(
                "deviceId", "start", "end", "direction", "msgId", "keyword", "limit");
        // 租户/部门绝不能出现在入参里：数据范围走 ToolSession，不经过模型。
        assertThat(params).doesNotContainKeys("tenantId", "departmentId", "scope");

        assertThat(params.get("deviceId")).contains("必填").contains("13800138000");
        assertThat(params.get("start")).contains("yyyy-MM-ddTHH:mm:ss").contains("2026-08-24");
        assertThat(params.get("end")).contains("2026-08-24");
        assertThat(params.get("direction")).contains("UP").contains("DOWN").contains("CONNECTION");
        // 十六进制/十进制等价是踩过的那一处：只认一种写法会「查不出来还不报错」。
        assertThat(params.get("msgId")).contains("0x0200").contains("512").contains("等价");
        assertThat(params.get("keyword")).contains("不搜原始 hex");
        assertThat(params.get("limit")).contains("默认 50").contains("最大 200");
    }

    @Test
    void theDetailToolSaysWhereTheIdComesFromAndWhenThereIsNoRawFrame() {
        Method method = toolMethod("get_device_log_detail");
        String description = method.getAnnotation(Tool.class).description();

        assertThat(description).contains("query_device_logs").contains("不要自己编造");
        // 「没有 hex」是正常情况而不是故障，不说清楚模型会报成查询失败。
        assertThat(description).contains("CONNECTION").contains("0x9206");

        Map<String, String> params = toolParams(method);
        assertThat(params).containsOnlyKeys("id");
        assertThat(params.get("id")).contains("必填").contains("整数")
                .contains("query_device_logs").contains("1287");
    }

    @Test
    void bothToolsAreExposedUnderTheirContractedNames() {
        assertThat(toolMethod("query_device_logs")).isNotNull();
        assertThat(toolMethod("get_device_log_detail")).isNotNull();
    }

    private static Method toolMethod(String name) {
        for (Method method : LogTools.class.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null && name.equals(tool.name())) {
                return method;
            }
        }
        throw new AssertionError("LogTools 没有暴露名为 " + name + " 的工具");
    }

    private static Map<String, String> toolParams(Method method) {
        Map<String, String> described = new LinkedHashMap<>();
        for (Parameter parameter : method.getParameters()) {
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            if (annotation != null) {
                described.put(parameter.getName(), annotation.description());
            }
        }
        return described;
    }
}
