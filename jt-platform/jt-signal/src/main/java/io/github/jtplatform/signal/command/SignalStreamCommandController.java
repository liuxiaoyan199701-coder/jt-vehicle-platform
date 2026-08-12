package io.github.jtplatform.signal.command;

import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamTicket;
import io.github.jtplatform.common.port.StreamCommandHandler;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/streams")
public class SignalStreamCommandController {
    private final StreamCommandHandler handler;

    public SignalStreamCommandController(StreamCommandHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @PostMapping("/open")
    public StreamTicket open(@RequestBody OpenCommand command) {
        Objects.requireNonNull(command, "command");
        StreamKey streamKey = new StreamKey(command.deviceId(), command.channel(),
                StreamKind.fromWireValue(command.streamKind()));
        return handler.openLive(streamKey, command.target());
    }

    @PostMapping("/playback")
    public StreamTicket playback(@RequestBody PlaybackCommand command) {
        Objects.requireNonNull(command, "command");
        StreamKey streamKey = new StreamKey(command.deviceId(), command.channel(),
                StreamKind.fromWireValue(command.streamKind()));
        return handler.openPlayback(
                streamKey,
                command.target(),
                LocalDateTime.parse(command.startTime()),
                command.endTime() == null ? null : LocalDateTime.parse(command.endTime()));
    }

    @PostMapping("/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@RequestBody CloseCommand command) {
        Objects.requireNonNull(command, "command");
        handler.close(new StreamKey(command.deviceId(), command.channel(),
                StreamKind.fromWireValue(command.streamKind())));
    }

    public record OpenCommand(String deviceId, int channel, String streamKind, MediaTarget target) {
        public OpenCommand {
            Objects.requireNonNull(target, "target");
        }
    }

    public record PlaybackCommand(
            String deviceId,
            int channel,
            String streamKind,
            MediaTarget target,
            String startTime,
            String endTime) {
        public PlaybackCommand {
            Objects.requireNonNull(target, "target");
            if (startTime == null || startTime.isBlank()) {
                throw new IllegalArgumentException("startTime is required");
            }
        }
    }

    public record CloseCommand(String deviceId, int channel, String streamKind) {
    }
}
