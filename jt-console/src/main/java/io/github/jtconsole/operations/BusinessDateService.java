package io.github.jtconsole.operations;

import io.github.jtconsole.config.ConsoleProperties;
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
            try {
                return LocalDateTime.parse(deviceTime.trim()).toLocalDate();
            } catch (DateTimeParseException ignored) {
                // 设备时间不可信时退回平台接收时间。
            }
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

    public Instant now() {
        return clock.instant();
    }

    public ZoneId zoneId() {
        return zoneId;
    }
}
