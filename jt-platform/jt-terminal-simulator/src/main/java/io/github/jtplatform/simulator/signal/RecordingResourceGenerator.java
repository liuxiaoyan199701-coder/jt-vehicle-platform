package io.github.jtplatform.simulator.signal;

import io.github.jtplatform.simulator.config.RecordingConfig;
import io.github.jtplatform.simulator.config.TerminalTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.yzh.protocol.t1078.T1205;

/** 根据配置生成确定性的合成录像资源列表。 */
public final class RecordingResourceGenerator {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId ZONE = TerminalTime.ZONE;

    public List<T1205.Item> generate(RecordingConfig config, java.time.Instant now) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(now, "now");
        if (config.resourceCount() == 0) {
            return List.of();
        }
        LocalDateTime end = resolve(config.endTime(), now);
        LocalDateTime start = resolve(config.startTime(), now);
        if (!end.isAfter(start)) {
            end = end.plusDays(1);
        }
        long totalMinutes = Math.max(config.resourceCount(), ChronoUnit.MINUTES.between(start, end));
        long segmentMinutes = Math.max(1, totalMinutes / config.resourceCount());
        List<T1205.Item> items = new ArrayList<>(config.resourceCount());
        for (int index = 0; index < config.resourceCount(); index++) {
            LocalDateTime itemStart = start.plusMinutes(segmentMinutes * index);
            LocalDateTime itemEnd = index == config.resourceCount() - 1
                    ? end : start.plusMinutes(segmentMinutes * (index + 1));
            items.add(new T1205.Item()
                    .setChannelNo(config.channel())
                    .setStartTime(itemStart)
                    .setEndTime(itemEnd)
                    .setWarnBit(0L)
                    .setMediaType(2)
                    .setStreamType(1)
                    .setStorageType(1)
                    .setSize(1_024L));
        }
        return List.copyOf(items);
    }

    public static LocalDateTime resolve(String value, java.time.Instant now) {
        LocalDateTime current = LocalDateTime.ofInstant(now, ZONE);
        return switch (value.toUpperCase(java.util.Locale.ROOT)) {
            case "NOW" -> current.withSecond(0).withNano(0);
            case "NOW-2H" -> current.minusHours(2).withSecond(0).withNano(0);
            default -> LocalDateTime.of(current.toLocalDate(), LocalTime.parse(value, CLOCK));
        };
    }
}
