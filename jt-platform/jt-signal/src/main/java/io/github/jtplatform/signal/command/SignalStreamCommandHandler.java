package io.github.jtplatform.signal.command;

import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import io.github.jtplatform.common.model.StreamTicket;
import io.github.jtplatform.common.port.StreamCommandException;
import io.github.jtplatform.common.port.StreamCommandHandler;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.yzh.protocol.commons.JT1078;
import org.yzh.protocol.t1078.T9101;
import org.yzh.protocol.t1078.T9102;
import org.yzh.protocol.t1078.T9201;
import org.yzh.protocol.t1078.T9202;
import org.yzh.web.endpoint.MessageManager;

public final class SignalStreamCommandHandler implements StreamCommandHandler {
    private static final DateTimeFormatter PROTOCOL_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    private final MessageManager messageManager;
    private final Clock clock;

    public SignalStreamCommandHandler(MessageManager messageManager, Clock clock) {
        this.messageManager = Objects.requireNonNull(messageManager, "messageManager");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StreamTicket openLive(StreamKey streamKey, MediaTarget target) {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(target, "target");
        if (streamKey.streamKind() == StreamKind.PLAYBACK) {
            throw new StreamCommandException("Playback requires an explicit recording time range");
        }
        messageManager.send(streamKey.deviceId(), createLiveCommand(streamKey, target));
        return ticket(streamKey, target);
    }

    @Override
    public void close(StreamKey streamKey) {
        Objects.requireNonNull(streamKey, "streamKey");
        if (streamKey.streamKind() == StreamKind.PLAYBACK) {
            messageManager.send(streamKey.deviceId(), createPlaybackCloseCommand(streamKey));
            return;
        }
        messageManager.send(streamKey.deviceId(), createCloseCommand(streamKey));
    }

    @Override
    public StreamTicket openPlayback(
            StreamKey streamKey,
            MediaTarget target,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(startTime, "startTime");
        if (streamKey.streamKind() != StreamKind.PLAYBACK) {
            throw new IllegalArgumentException("streamKind must be playback");
        }
        if (endTime != null && endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("endTime must not be before startTime");
        }
        messageManager.send(streamKey.deviceId(), createPlaybackCommand(streamKey, target, startTime, endTime));
        return ticket(streamKey, target);
    }

    static T9101 createLiveCommand(StreamKey streamKey, MediaTarget target) {
        T9101 command = new T9101()
                .setIp(target.reachableAddress())
                .setTcpPort(target.tcpPort())
                .setUdpPort(target.udpPort())
                .setChannelNo(streamKey.channel())
                .setMediaType(streamKey.streamKind().dataType())
                .setStreamType(streamKey.streamKind().streamType());
        command.setMessageId(JT1078.实时音视频传输请求);
        command.setClientId(streamKey.deviceId());
        return command;
    }

    static T9102 createCloseCommand(StreamKey streamKey) {
        int command = streamKey.streamKind() == StreamKind.TALKBACK ? 4 : 0;
        T9102 commandMessage = new T9102()
                .setChannelNo(streamKey.channel())
                .setCommand(command)
                .setCloseType(0)
                .setStreamType(streamKey.streamKind().streamType());
        commandMessage.setMessageId(JT1078.音视频实时传输控制);
        commandMessage.setClientId(streamKey.deviceId());
        return commandMessage;
    }

    static T9202 createPlaybackCloseCommand(StreamKey streamKey) {
        T9202 command = new T9202()
                .setChannelNo(streamKey.channel())
                .setPlaybackMode(2)
                .setPlaybackSpeed(0)
                .setPlaybackTime("000000000000");
        command.setMessageId(JT1078.平台下发远程录像回放控制);
        command.setClientId(streamKey.deviceId());
        return command;
    }

    static T9201 createPlaybackCommand(
            StreamKey streamKey,
            MediaTarget target,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        T9201 command = new T9201()
                .setIp(target.reachableAddress())
                .setTcpPort(target.tcpPort())
                .setUdpPort(target.udpPort())
                .setChannelNo(streamKey.channel())
                .setMediaType(0)
                .setStreamType(0)
                .setStorageType(0)
                .setPlaybackMode(0)
                .setPlaybackSpeed(0)
                .setStartTime(PROTOCOL_TIME.format(startTime))
                .setEndTime(endTime == null ? "000000000000" : PROTOCOL_TIME.format(endTime));
        command.setMessageId(JT1078.平台下发远程录像回放请求);
        command.setClientId(streamKey.deviceId());
        return command;
    }

    private StreamTicket ticket(StreamKey streamKey, MediaTarget target) {
        return new StreamTicket(streamKey, streamKey.externalId(), target, target.websocketUri("/ws"),
                StreamState.PENDING, clock.instant());
    }
}
