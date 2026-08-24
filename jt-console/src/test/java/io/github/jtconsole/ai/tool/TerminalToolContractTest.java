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
 * 参数契约与那条权限边界说明必须真的送达模型。
 *
 * <p>Spring AI 由注解反射生成 schema，写在注解外的说明模型一个字也看不到。
 * 这已经栽过两次（动作清单只报动作名、报文日志工具的格式示例），第三次不该再靠人记得。
 */
class TerminalToolContractTest {

    @Test
    void theToolTellsTheModelEveryParameterItsValuesAndAnExample() {
        Method method = toolMethod("query_terminals");
        Map<String, String> params = toolParams(method);

        assertThat(params).containsOnlyKeys("keyword", "archived", "online", "limit");
        // 租户/部门绝不能出现在入参里：数据范围走 ToolSession，不经过模型。
        assertThat(params).doesNotContainKeys("tenantId", "departmentId", "scope");

        assertThat(params.get("keyword")).contains("终端 ID").contains("车牌").contains("1380000");
        assertThat(params.get("archived")).contains("true").contains("false").contains("没建档");
        // 在线与「最近注册」是两回事，不说清楚模型会把两者当同一个意思转述。
        assertThat(params.get("online")).contains("两回事");
        assertThat(params.get("limit")).contains("默认 20").contains("最大 100");
    }

    /**
     * 空结果在租户会话下是权限边界，不是故障。描述里不写这一条，
     * 模型就会把「你看不到未建档终端」说成「查询失败」。
     */
    @Test
    void theDescriptionExplainsThatUnarchivedTerminalsArePlatformOnly() {
        String description = toolMethod("query_terminals").getAnnotation(Tool.class).description();

        assertThat(description).contains("未建档终端只对平台管理员可见");
        assertThat(description).contains("不要说成查询失败");
        // 自报值不可信这件事必须传达，否则 AI 会把它当成档案信息转述给用户。
        assertThat(description).contains("自报").contains("未经核实");
        // 只读：建档要人确认车牌，模型不该以为自己能代劳。
        assertThat(description).contains("只读").contains("不能建档");
    }

    private static Method toolMethod(String name) {
        for (Method method : TerminalTools.class.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null && name.equals(tool.name())) {
                return method;
            }
        }
        throw new AssertionError("TerminalTools 没有暴露名为 " + name + " 的工具");
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
