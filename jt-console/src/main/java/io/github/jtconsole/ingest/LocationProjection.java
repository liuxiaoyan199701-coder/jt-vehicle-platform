package io.github.jtconsole.ingest;

import io.github.jtconsole.operations.DailyStatService;
import io.github.jtconsole.repository.StatusRepository;
import io.github.jtconsole.repository.TrackRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 把一个已解析的位置写进轨迹、设备状态与日统计。
 *
 * <p>抽出来是为了让单点（0x0200）与批量补传（0x0704）走同一段落库逻辑：两者在「写什么」上完全一致，
 * 差异只在「写完之后做什么」——单点会继续触发告警、围栏与实时推送，补传点不会。那部分留在
 * {@link LocationService}，这里只负责持久化。
 */
@Service
public class LocationProjection {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationProjection.class);

    private final TrackRepository tracks;
    private final StatusRepository statuses;
    private final DailyStatService dailyStats;
    private final ObjectMapper objectMapper;

    public LocationProjection(
            TrackRepository tracks,
            StatusRepository statuses,
            DailyStatService dailyStats,
            ObjectMapper objectMapper) {
        this.tracks = tracks;
        this.statuses = statuses;
        this.dailyStats = dailyStats;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入一个可用坐标的位置。
     *
     * @param sample 必须是 {@link LocationSample#locatable()} 为 true 的样本
     */
    public Outcome project(LocationSample sample) {
        Outcome outcome = write(sample);
        if (outcome.trackInserted()) {
            dailyStats.record(sample.deviceId(), sample.deviceTime(), sample.receivedAt(),
                    sample.lat(), sample.lng(), sample.speedKph(), sample.mileage());
        }
        return outcome;
    }

    /**
     * 批量写入一组同设备的位置，日统计按自然日聚合后一次性更新。
     *
     * @param samples 必须全部 {@link LocationSample#locatable()}，且已按设备时间升序排列
     * @return 真正入库的点数
     */
    public int projectAll(List<LocationSample> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        List<DailyStatService.Point> counted = new ArrayList<>(samples.size());
        for (LocationSample sample : samples) {
            if (write(sample).trackInserted()) {
                counted.add(new DailyStatService.Point(sample.deviceTime(), sample.receivedAt(),
                        sample.lat(), sample.lng(), sample.speedKph(), sample.mileage()));
            }
        }
        dailyStats.recordAll(samples.getFirst().deviceId(), counted);
        return counted.size();
    }

    /**
     * 写轨迹与设备状态。日统计留给调用方——单点立即累加，批量聚合后一次累加。
     *
     * <p>只有真正入库的点才该计入日统计：补传窗口与实时上报重叠时，重复点会把点数、
     * 移动点数与最高速度重复抬高一遍，而里程本来就靠设备时间递增判定挡住了。
     */
    private Outcome write(LocationSample sample) {
        String alarmJson = writeJson(sample.activeAlarms());
        String statusJson = writeJson(sample.activeStatusFlags());

        boolean inserted = tracks.insert(sample.deviceId(), sample.deviceTime(), sample.receivedAt(),
                sample.lat(), sample.lng(), sample.gcjLat(), sample.gcjLng(),
                sample.speedKph(), sample.direction(), sample.altitude(), sample.mileage(), alarmJson);

        boolean latest = statuses.upsertLocation(sample.deviceId(), sample.deviceTime(),
                sample.receivedAt(), sample.lat(), sample.lng(), sample.gcjLat(), sample.gcjLng(),
                sample.speedKph(), sample.direction(), sample.altitude(), sample.mileage(),
                sample.accOn(), sample.positioned(), alarmJson, statusJson);

        return new Outcome(inserted, latest, alarmJson);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException failure) {
            LOGGER.warn("Failed to serialize flags", failure);
            return null;
        }
    }

    /**
     * @param trackInserted 是否写入了新轨迹点；false 表示该设备时间已有点，本次被去重忽略
     * @param latest        是否成为该设备的最新状态；false 表示已存状态的设备时间更晚
     * @param alarmJson     序列化后的活动告警，供调用方复用而不必重复序列化
     */
    public record Outcome(boolean trackInserted, boolean latest, String alarmJson) {
    }
}
