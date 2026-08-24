package io.github.jtconsole.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 页面按 {@code msgIdHex} 显示「消息 ID」这一列。
 *
 * <p>record 默认只序列化它的 component，{@code msgIdHex()} 是派生方法——不显式标注
 * {@code @JsonProperty} 就根本不进 JSON，而前端拿不到字段只会安静地显示「-」，
 * 接口照样 200，没有任何地方会报错。2026-08-24 发布后在真机上就是这么发现的。
 */
class DeviceLogSerializationTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void theHexMessageIdReachesTheBrowserAlongsideTheDecimalOne() {
        String json = mapper.writeValueAsString(log(0x0100));

        assertThat(json).contains("\"msgId\":256").contains("\"msgIdHex\":\"0x0100\"");
    }

    @Test
    void aRowWithoutAMessageIdKeepsTheFieldPresentAndNull() {
        String json = mapper.writeValueAsString(log(null));

        // 键必须在，值为 null——键直接消失会让前端分不清「没有消息 ID」与「后端漏发字段」。
        assertThat(json).contains("\"msgIdHex\":null");
    }

    @Test
    void everyColumnThePageRendersIsPresent() {
        String json = mapper.writeValueAsString(log(0x8801));

        assertThat(json).contains("\"logTime\"").contains("\"direction\"").contains("\"serialNo\"")
                .contains("\"summary\"").contains("\"rawHex\"").contains("\"parsedJson\"")
                .contains("\"decodeError\"").contains("\"truncated\"").contains("\"instanceId\"");
    }

    private static DeviceLog log(Integer msgId) {
        return new DeviceLog(1, "evt-1", "13800138000", 1L, "UP", msgId, 7,
                "2026-08-24T09:02:03.000+08:00", "位置信息汇报", "7e0200",
                "{\"speedKph\":6.0}", false, false, "signal-1");
    }
}
