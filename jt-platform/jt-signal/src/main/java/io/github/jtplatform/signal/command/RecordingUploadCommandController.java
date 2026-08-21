package io.github.jtplatform.signal.command;

import io.github.jtplatform.common.port.RecordingUploadCredentialPort;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yzh.protocol.t1078.T9206;
import org.yzh.protocol.t808.T0001;
import org.yzh.web.endpoint.MessageManager;
import reactor.core.publisher.Mono;

/** Builds 0x9206 with a freshly issued task-scoped FTP credential. */
@RestController
@RequestMapping("/device")
public final class RecordingUploadCommandController {
    private final MessageManager messages;
    private final ObjectProvider<RecordingUploadCredentialPort> credentialProvider;

    public RecordingUploadCommandController(
            MessageManager messages,
            ObjectProvider<RecordingUploadCredentialPort> credentialProvider) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.credentialProvider = Objects.requireNonNull(credentialProvider, "credentialProvider");
    }

    @PostMapping("/9206")
    public Mono<UploadCommandResponse> upload(@RequestBody UploadCommand request) {
        request.validate();
        RecordingUploadCredentialPort credentials = credentialProvider.getIfAvailable();
        if (credentials == null) {
            return Mono.error(new IllegalStateException("Recording FTP service is unavailable"));
        }
        var lease = credentials.issue(request.taskId(), request.deviceId());
        T9206 command = new T9206()
                .setIp(lease.serverAddress())
                .setPort(lease.port())
                .setUsername(lease.username())
                .setPassword(lease.password())
                .setPath(lease.path())
                .setChannelNo(request.channel())
                .setStartTime(request.startTime())
                .setEndTime(request.endTime())
                .setWarnBit1((int) request.warnBit1())
                .setWarnBit2((int) request.warnBit2())
                .setMediaType(request.mediaType())
                .setStreamType(request.streamType())
                .setStorageType(request.storageType())
                .setCondition(request.condition());
        command.setClientId(request.deviceId());
        Mono<T0001> response;
        try {
            response = messages.request(request.deviceId(), command, T0001.class);
            credentials.bindCommand(request.taskId(), request.deviceId(), command.getSerialNo());
        } catch (RuntimeException failure) {
            credentials.revokeTask(request.taskId());
            throw failure;
        }
        return response.map(ack -> {
                    if (!ack.isSuccess()) credentials.revokeTask(request.taskId());
                    return new UploadCommandResponse(
                            request.taskId(), command.getSerialNo(), ack.isSuccess(), lease.expiresAt());
                })
                .doOnError(ignored -> credentials.revokeTask(request.taskId()));
    }

    public record UploadCommand(
            String taskId,
            String deviceId,
            int channel,
            LocalDateTime startTime,
            LocalDateTime endTime,
            long warnBit1,
            long warnBit2,
            int mediaType,
            int streamType,
            int storageType,
            int condition) {
        void validate() {
            if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId must not be blank");
            if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("deviceId must not be blank");
            if (channel < 1 || channel > 255) throw new IllegalArgumentException("channel must be in range 1..255");
            Objects.requireNonNull(startTime, "startTime");
            Objects.requireNonNull(endTime, "endTime");
            if (!endTime.isAfter(startTime)) throw new IllegalArgumentException("endTime must be after startTime");
            if (warnBit1 < 0 || warnBit1 > 0xffff_ffffL || warnBit2 < 0 || warnBit2 > 0xffff_ffffL) {
                throw new IllegalArgumentException("alarm bits must be unsigned 32-bit values");
            }
            if (mediaType < 0 || mediaType > 3) throw new IllegalArgumentException("mediaType must be in range 0..3");
            if (streamType < 0 || streamType > 2) throw new IllegalArgumentException("streamType must be in range 0..2");
            if (storageType < 0 || storageType > 2) throw new IllegalArgumentException("storageType must be in range 0..2");
            if (condition < 0 || condition > 7) throw new IllegalArgumentException("condition must be in range 0..7");
        }
    }

    public record UploadCommandResponse(
            String taskId, int commandSerialNo, boolean accepted, Instant credentialExpiresAt) { }
}
