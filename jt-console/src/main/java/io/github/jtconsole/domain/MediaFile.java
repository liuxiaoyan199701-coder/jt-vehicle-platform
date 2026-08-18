package io.github.jtconsole.domain;

/**
 * 终端上传的多媒体文件元数据。{@code accessAddress} 由网关的
 * {@code multimedia-access-base-url} 决定，未配置时为空——前端需要做降级展示。
 *
 * <p>四个坐标列可空，且**没有位置时是 null 而不是 0**：设备未定位与「拍于赤道以西的几内亚湾」
 * 必须能区分开，否则地图上会多出一个看不出是假的点。取用前先判 {@link #locatable()}。
 *
 * <p>{@code eventCode} 是 0x0801 的事件项编码（0 平台下发指令、1 定时动作、2 抢劫报警触发、
 * 3 碰撞侧翻报警触发）。**它不是告警 ID**——协议没有提供任何能定位到具体那条告警的字段，
 * 所以本记录与 {@code alarm_event} 之间不存在外键，关联只能在读取时按设备与时间窗联查。
 */
public record MediaFile(
        Long id,
        String deviceId,
        Long fileId,
        String fileType,
        String fileFormat,
        String fileName,
        Long size,
        String accessAddress,
        Integer channelId,
        Integer eventCode,
        Double lat,
        Double lng,
        Double gcjLat,
        Double gcjLng,
        String capturedAt) {

    /** 是否带有可用坐标。前端据此决定显示地图还是「无位置信息」。 */
    public boolean locatable() {
        return gcjLat != null && gcjLng != null;
    }

    /** 是否由报警触发而非平台指令或定时动作。这一条来自协议，可信。 */
    public boolean alarmTriggered() {
        return eventCode != null && eventCode >= 2;
    }
}
