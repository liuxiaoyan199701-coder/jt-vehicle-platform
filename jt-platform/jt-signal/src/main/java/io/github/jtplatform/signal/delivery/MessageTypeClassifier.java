package io.github.jtplatform.signal.delivery;

import io.github.jtplatform.delivery.model.MessageType;
import io.github.yezhihao.netmc.core.model.Response;
import java.util.Map;
import java.util.Set;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.commons.transform.attribute.Alarm;
import org.yzh.protocol.jsatl12.DataPacket;
import org.yzh.protocol.t1078.T9105;
import org.yzh.protocol.t808.T0200;

public final class MessageTypeClassifier {
    private static final Set<Integer> ALARM_IDS = Set.of(
            0x9208, 0x1210, 0x1211, 0x1212, 0x9212, 0x30316364);
    private static final Set<Integer> MULTIMEDIA_IDS = Set.of(
            0x0800, 0x0801, 0x0802, 0x0805,
            0x8800, 0x8801, 0x8802, 0x8803, 0x8804, 0x8805,
            0x9101, 0x9102, 0x9105,
            0x9301, 0x9302, 0x9303, 0x9304, 0x9305, 0x9306);
    private static final Set<Integer> RECORDING_METADATA_IDS = Set.of(
            0x1205, 0x1206, 0x9201, 0x9202, 0x9205, 0x9206, 0x9207);
    private static final Set<Integer> TERMINAL_PARAMETER_IDS = Set.of(
            0x0104, 0x0107, 0x8103, 0x8104, 0x8106, 0x8107, 0x8701, 0x1003, 0x9003);

    public MessageType classify(JTMessage message) {
        int id = message.getMessageId();
        if (message instanceof T0200 location) {
            return hasAlarm(location) ? MessageType.ALARM : MessageType.LOCATION;
        }
        if (message instanceof DataPacket || ALARM_IDS.contains(id)) {
            return MessageType.ALARM;
        }
        if (id == JT808.定位数据批量上传) {
            return MessageType.BATCH_LOCATION;
        }
        if (id == JT808.电子运单上报) {
            return MessageType.WAYBILL;
        }
        if (id == JT808.终端心跳) {
            return MessageType.HEARTBEAT;
        }
        if (id == JT808.终端注册 || id == JT808.终端注册应答) {
            return MessageType.REGISTER;
        }
        if (id == JT808.终端鉴权) {
            return MessageType.AUTHENTICATION;
        }
        if (message instanceof T9105 || MULTIMEDIA_IDS.contains(id)) {
            return MessageType.MULTIMEDIA;
        }
        if (RECORDING_METADATA_IDS.contains(id)) {
            return MessageType.RECORDING_METADATA;
        }
        if (TERMINAL_PARAMETER_IDS.contains(id)) {
            return MessageType.TERMINAL_PARAMETER;
        }
        if (message instanceof Response
                || id == JT808.终端通用应答
                || id == JT808.位置信息查询应答
                || id == JT808.车辆控制应答
                || id == JT808.终端升级结果通知
                || id == JT808.查询区域或线路数据应答
                || id == JT808.行驶记录数据上传
                || id == JT808.驾驶员身份信息采集上报) {
            return MessageType.CONTROL_RESULT;
        }
        if (id == JT808.数据上行透传 || id == JT808.数据压缩上报 || id == JT808.数据下行透传) {
            return MessageType.TRANSPARENT_DATA;
        }
        return MessageType.OTHER;
    }

    private static boolean hasAlarm(T0200 location) {
        if (location.getWarnBit() != 0) {
            return true;
        }
        Map<Integer, Object> attributes = location.getAttributes();
        return attributes != null && attributes.values().stream().anyMatch(Alarm.class::isInstance);
    }
}
