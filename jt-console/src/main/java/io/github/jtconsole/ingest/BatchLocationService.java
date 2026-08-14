package io.github.jtconsole.ingest;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.repository.StatusRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 处理 0x0704 定位数据批量上传。
 *
 * <p>批量点是历史数据：它描述的是几分钟到几小时前的时刻。因此这条路径只写轨迹、设备状态与日统计，
 * <b>不</b>做告警同步、<b>不</b>做围栏判定、<b>不</b>推送实时位置。用补传数据反推围栏进出，会在设备
 * 早已驶离时生成一条「刚刚进入围栏」的告警；用它同步告警活动状态，会让一条几小时前就该结束的
 * 超速告警重新变成活动。单点路径上「非最新点跳过告警与围栏」是同一个道理，这里只是把它前置为无条件。
 */
@Service
public class BatchLocationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchLocationService.class);

    private final LocationProjection projection;
    private final StatusRepository statuses;
    private final ConsoleProperties properties;

    public BatchLocationService(
            LocationProjection projection,
            StatusRepository statuses,
            ConsoleProperties properties) {
        this.projection = projection;
        this.statuses = statuses;
        this.properties = properties;
    }

    public LocationHandlingResult handle(MessageEnvelope envelope, String receivedAt) {
        String deviceId = envelope.deviceId();
        // 批量报文本身就是设备在线的证据，即使一个点都用不了也要刷新在线时间
        statuses.touch(deviceId, receivedAt);

        List<?> items = itemsOf(envelope.payload());
        if (items.isEmpty()) {
            return LocationHandlingResult.withoutLiveUpdate("batch-empty");
        }
        warnOnTotalMismatch(envelope, items.size());

        List<LocationSample> usable = new ArrayList<>(items.size());
        int unpositioned = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> values)) {
                unpositioned++;
                continue;
            }
            LocationSample sample = LocationSample.parse(deviceId, receivedAt, asPayload(values));
            if (sample.locatable()) {
                usable.add(sample);
            } else {
                unpositioned++;
            }
        }

        // 设备时间是无时区的 ISO 字符串，字典序即时间序。里程累加要求严格递增，必须先排好。
        usable.sort(Comparator.comparing(
                LocationSample::deviceTime, Comparator.nullsLast(Comparator.naturalOrder())));

        int limit = properties.getIngest().getMaxBatchPoints();
        int truncated = Math.max(0, usable.size() - limit);
        if (truncated > 0) {
            // 保留最近的一段：当前位置必须是对的，而且操作员看的就是最新的那截轨迹。
            usable = usable.subList(usable.size() - limit, usable.size());
            LOGGER.warn("设备 {} 的批量定位报文有 {} 个可用点，超过上限 {}，丢弃最早的 {} 个",
                    deviceId, truncated + limit, limit, truncated);
        }

        int stored = projection.projectAll(usable);
        return LocationHandlingResult.withoutLiveUpdate(outcome(stored, items.size(), unpositioned, truncated));
    }

    private static String outcome(int stored, int total, int unpositioned, int truncated) {
        StringBuilder outcome = new StringBuilder("batch-located stored=")
                .append(stored).append('/').append(total);
        if (unpositioned > 0) {
            outcome.append(" unpositioned=").append(unpositioned);
        }
        if (truncated > 0) {
            outcome.append(" truncated=").append(truncated);
        }
        return outcome.toString();
    }

    private static void warnOnTotalMismatch(MessageEnvelope envelope, int actual) {
        Object total = envelope.payload().get("total");
        if (total instanceof Number declared && declared.intValue() != actual) {
            // 不因此拒收——终端声称的数量对不上是它自己的实现问题，但数据本身仍然有价值。
            LOGGER.warn("设备 {} 的批量定位报文声称 {} 个点，实际携带 {} 个",
                    envelope.deviceId(), declared.intValue(), actual);
        }
    }

    private static List<?> itemsOf(Map<String, Object> payload) {
        return payload != null && payload.get("items") instanceof List<?> items ? items : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asPayload(Map<?, ?> values) {
        return (Map<String, Object>) values;
    }
}
