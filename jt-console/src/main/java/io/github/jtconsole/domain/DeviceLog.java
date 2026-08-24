package io.github.jtconsole.domain;

/**
 * 一条设备日志。三个方向共用一张表，设备时间线因此单表可查。
 *
 * @param tenantId     入库时刻的归属，未建档设备为空；不追溯回填
 * @param direction    {@code UP} 上行报文、{@code DOWN} 下行指令、{@code CONNECTION} 上下线
 * @param msgId        808 消息 ID 的十进制值；连接事件与解码失败帧为空
 * @param rawHex       原始帧十六进制；连接事件没有报文，含一次性口令的报文也不留
 * @param parsedJson   解析后的报文正文；解码失败时为空
 * @param decodeError  该帧解码失败，只有原始字节可信
 * @param truncated    原始 hex 或解析 JSON 触到网关侧单条上限被截断
 */
public record DeviceLog(
        long id,
        String eventId,
        String deviceId,
        Long tenantId,
        String direction,
        Integer msgId,
        Integer serialNo,
        String logTime,
        String summary,
        String rawHex,
        String parsedJson,
        boolean decodeError,
        boolean truncated,
        String instanceId) {

    /** 十六进制写法的消息 ID，页面与 AI 结果都按这个口径展示。 */
    public String msgIdHex() {
        return msgId == null ? null : String.format("0x%04X", msgId);
    }
}
