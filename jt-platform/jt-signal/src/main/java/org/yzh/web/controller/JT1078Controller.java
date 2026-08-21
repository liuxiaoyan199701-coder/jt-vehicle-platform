package org.yzh.web.controller;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamTicket;
import io.github.jtplatform.common.service.StreamCoordinator;
import java.time.LocalDateTime;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT1078;
import org.yzh.protocol.jsatl12.T9208;
import org.yzh.protocol.t1078.*;
import org.yzh.protocol.t808.T0001;
import org.yzh.web.endpoint.MessageManager;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/device")
public class JT1078Controller {
    private final MessageManager messageManager;
    private final StreamCoordinator streamCoordinator;

    public JT1078Controller(MessageManager messageManager, StreamCoordinator streamCoordinator) {
        this.messageManager = messageManager;
        this.streamCoordinator = streamCoordinator;
    }

    @PostMapping("/9003")
    public Mono<T1003> T9003(@RequestBody JTMessage request) {
        return messageManager.request(request.setMessageId(JT1078.查询终端音视频属性), T1003.class);
    }

    @PostMapping("/9101")
    public StreamTicket T9101(@RequestBody LiveCommand command) {
        StreamKey key = new StreamKey(command.deviceId(), command.channel(),
                StreamKind.fromWireValue(command.streamKind()));
        return streamCoordinator.open(key);
    }

    @PostMapping("/9102")
    public Mono<T0001> T9102(@RequestBody T9102 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/9201")
    public StreamTicket T9201(@RequestBody PlaybackCommand command) {
        StreamKey key = new StreamKey(command.deviceId(), command.channel(), StreamKind.PLAYBACK);
        return streamCoordinator.openPlayback(key, command.startTime(), command.endTime());
    }

    @PostMapping("/9202")
    public Mono<T0001> T9202(@RequestBody T9202 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/9205")
    public Mono<T1205> T9205(@RequestBody T9205 request) {
        return messageManager.request(request, T1205.class);
    }

    @PostMapping("/9207")
    public Mono<T0001> T9207(@RequestBody T9207 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/9208")
    public Mono<T0001> T9208(@RequestBody T9208 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/9301")
    public Mono<T0001> T9301(@RequestBody T9301 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/9302")
    public Mono<T0001> T9302(@RequestBody T9302 request) {
        return messageManager.request(request.setMessageId(JT1078.云台调整焦距控制), T0001.class);
    }

    @PostMapping("/9303")
    public Mono<T0001> T9303(@RequestBody T9302 request) {
        return messageManager.request(request.setMessageId(JT1078.云台调整光圈控制), T0001.class);
    }

    @PostMapping("/9304")
    public Mono<T0001> T9304(@RequestBody T9302 request) {
        return messageManager.request(request.setMessageId(JT1078.云台雨刷控制), T0001.class);
    }

    @PostMapping("/9305")
    public Mono<T0001> T9305(@RequestBody T9302 request) {
        return messageManager.request(request.setMessageId(JT1078.红外补光控制), T0001.class);
    }

    @PostMapping("/9306")
    public Mono<T0001> T9306(@RequestBody T9302 request) {
        return messageManager.request(request.setMessageId(JT1078.云台变倍控制), T0001.class);
    }

    public record LiveCommand(
            String deviceId,
            int channel,
            String streamKind) {
    }

    public record PlaybackCommand(
            String deviceId,
            int channel,
            LocalDateTime startTime,
            LocalDateTime endTime) {
    }
}
