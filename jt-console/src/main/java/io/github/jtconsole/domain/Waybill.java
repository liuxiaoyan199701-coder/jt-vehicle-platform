package io.github.jtconsole.domain;

/** 终端上报的电子运单展示摘要。原始字节只通过下载端点返回，不进入列表 JSON。 */
public record Waybill(
        long id,
        String deviceId,
        String reportedAt,
        String receivedAt,
        int rawLength,
        String preview,
        boolean utf8) {
}
