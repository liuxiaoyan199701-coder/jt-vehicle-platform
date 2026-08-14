package io.github.jtconsole.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * jt-platform 网关投递过来的消息信封。
 *
 * <p>结构由网关的 {@code JacksonMessageEnvelopeEncoder} 固定为 9 个顶层字段。注意：
 * <ul>
 *   <li>{@code messageId} 是十进制无符号整数，0x0200 位置汇报对应 {@code 512}</li>
 *   <li>{@code receivedAt} 是 UTC ISO-8601 带 {@code Z}，而 {@code payload.deviceTime}
 *       是无时区的本地时间字符串，两者格式不同</li>
 *   <li>{@code type} 对位置汇报可能是 {@code location} 也可能是 {@code alarm}（带报警位时），
 *       因此判断位置消息要用 {@code messageId == 512} 而不是 {@code type}</li>
 * </ul>
 *
 * <p>用 String 而非 Instant 接收时间字段，是为了让任何格式异常都不至于让请求变成 4xx——
 * 网关对非 2xx 会重试，脏数据引发的重试风暴比丢一条消息更糟。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageEnvelope(
        String eventId,
        String deviceId,
        Long messageId,
        Integer serialNo,
        String protocolVersion,
        String receivedAt,
        String instanceId,
        String type,
        Map<String, Object> payload) {

    /** JT/T 808 位置信息汇报 0x0200 */
    public static final long LOCATION_REPORT = 0x0200L;

    /** JT/T 808 定位数据批量上传 0x0704 */
    public static final long BATCH_LOCATION_REPORT = 0x0704L;

    /** 单点位置汇报。批量报文不算在内，两者的处理路径不同。 */
    public boolean isLocationReport() {
        return messageId != null && messageId == LOCATION_REPORT;
    }

    /**
     * 定位数据批量上传。{@code payload.items} 是一组与 0x0200 形态一致的位置。
     *
     * <p>注意 {@code payload.type}（0 正常批量 / 1 盲区补报）与信封的 {@link #type()}
     * （{@code location}/{@code alarm}）同名不同义。
     */
    public boolean isBatchLocationReport() {
        return messageId != null && messageId == BATCH_LOCATION_REPORT;
    }
}
