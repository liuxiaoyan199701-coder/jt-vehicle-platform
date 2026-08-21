package io.github.jtplatform.simulator.signal;

import io.github.jtplatform.simulator.config.TerminalTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.yzh.protocol.t1078.T9201;

/** 经过协议字段校验后的 0x9201 回放请求。 */
public record RecordingPlaybackRequest(
        String host,
        int tcpPort,
        int udpPort,
        int channel,
        int mediaType,
        int streamType,
        LocalDateTime startTime,
        LocalDateTime endTime) {
    private static final DateTimeFormatter PROTOCOL_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final ZoneId ZONE = TerminalTime.ZONE;

    public static RecordingPlaybackRequest from(T9201 command) {
        Objects.requireNonNull(command, "command");
        if (command.getIp() == null || command.getIp().isBlank()) {
            throw new IllegalArgumentException("9201 server IP must not be blank");
        }
        if (command.getTcpPort() < 1 || command.getTcpPort() > 65_535) {
            throw new IllegalArgumentException("9201 TCP port must be in range 1..65535");
        }
        if (command.getChannelNo() < 1 || command.getChannelNo() > 255) {
            throw new IllegalArgumentException("9201 channel must be in range 1..255");
        }
        LocalDateTime start = parse(command.getStartTime());
        LocalDateTime end = parse(command.getEndTime());
        if (end != null && !end.isAfter(start)) {
            throw new IllegalArgumentException("9201 end time must be after start time");
        }
        return new RecordingPlaybackRequest(command.getIp().trim(), command.getTcpPort(),
                command.getUdpPort(), command.getChannelNo(), command.getMediaType(),
                command.getStreamType(), start, end);
    }

    private static LocalDateTime parse(String value) {
        if (value == null || value.isBlank() || value.matches("0+")) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, PROTOCOL_TIME);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid 9201 time: " + value, failure);
        }
    }
}
