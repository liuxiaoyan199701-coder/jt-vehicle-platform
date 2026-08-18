package io.github.jtconsole.operations;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.config.Timestamps;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class BusinessDateService {

    private final ZoneId zoneId;
    private final Clock clock;

    @Autowired
    public BusinessDateService(ConsoleProperties properties) {
        this(properties, Clock.systemUTC());
    }

    BusinessDateService(ConsoleProperties properties, Clock clock) {
        String configured = properties.getOperations().getZoneId();
        try {
            this.zoneId = ZoneId.of(configured == null ? "" : configured.trim());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("无效的运营业务时区", invalid);
        }
        this.clock = clock;
    }

    public LocalDate resolve(String deviceTime, String receivedAt) {
        if (deviceTime != null && !deviceTime.isBlank()) {
            LocalDate fromDevice = toLocalDate(deviceTime.trim());
            if (fromDevice != null) {
                return fromDevice;
            }
            // 设备时间不可信时退回平台接收时间。
        }
        if (receivedAt != null && !receivedAt.isBlank()) {
            try {
                return Instant.parse(receivedAt.trim()).atZone(zoneId).toLocalDate();
            } catch (DateTimeParseException ignored) {
                // 最终使用注入时钟，避免依赖服务器默认时区。
            }
        }
        return LocalDate.now(clock.withZone(zoneId));
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zoneId));
    }

    /**
     * 取设备时间的日历日。
     *
     * <p>两种写法都要认：带偏移的（{@code …T08:01:00+08:00}，现行口径）与无偏移的
     * （{@code …T08:01:00}，历史数据与部分外部来源）。**只认一种是不够的**——
     * {@link LocalDateTime#parse} 遇到带偏移的值会抛异常，而这里的失败是静默回落到接收时间，
     * 于是跨零点的补传会整批被归到同一天，日里程随之算错，且不报任何错。
     *
     * <p>带偏移时取其自身偏移下的日历日，而不是换算到运营时区：设备时间表达的就是终端所在地的
     * 墙上时间，「这一天」按终端的一天算才对得上司机的认知。
     */
    private static LocalDate toLocalDate(String deviceTime) {
        return Timestamps.toLocalDateTime(deviceTime).map(LocalDateTime::toLocalDate).orElse(null);
    }

    public Instant now() {
        return clock.instant();
    }

    public ZoneId zoneId() {
        return zoneId;
    }
}
