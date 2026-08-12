package io.github.jtplatform.boot.cluster;

import static io.github.jtplatform.boot.cluster.ClusterStateProtocol.Ack;

import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import io.github.jtplatform.common.port.StreamRegistry;
import io.github.jtplatform.common.service.StreamCoordinator;
import java.time.Duration;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

@RestController
@RequestMapping("/internal/cluster-state")
@Profile("runtime-api")
final class ClusterStateController {
    private final MediaInstanceRegistry mediaInstances;
    private final StreamRegistry streams;
    private final StreamTokenStore tokens;
    private final StreamCoordinator coordinator;

    ClusterStateController(
            MediaInstanceRegistry mediaInstances,
            StreamRegistry streams,
            StreamTokenStore tokens,
            StreamCoordinator coordinator) {
        this.mediaInstances = mediaInstances;
        this.streams = streams;
        this.tokens = tokens;
        this.coordinator = coordinator;
    }

    @GetMapping("/health")
    Ack health() {
        return Ack.ok();
    }

    @PostMapping("/media/register")
    Ack registerMedia(@RequestBody ClusterStateProtocol.MediaInstanceData request) {
        mediaInstances.register(request.toDomain());
        return Ack.ok();
    }

    @PostMapping("/media/find")
    ClusterStateProtocol.MediaLookupResult findMedia(
            @RequestBody ClusterStateProtocol.InstanceIdRequest request) {
        return new ClusterStateProtocol.MediaLookupResult(mediaInstances.find(request.instanceId())
                .map(ClusterStateProtocol.MediaInstanceData::from)
                .orElse(null));
    }

    @PostMapping("/media/update-load")
    Ack updateMediaLoad(@RequestBody ClusterStateProtocol.MediaLoadRequest request) {
        mediaInstances.updateLoad(request.instanceId(), request.currentStreams(),
                request.outboundBitsPerSecond(), Instant.parse(request.heartbeatAt()));
        return Ack.ok();
    }

    @PostMapping("/media/draining")
    Ack markMediaDraining(@RequestBody ClusterStateProtocol.MediaHeartbeatRequest request) {
        mediaInstances.markDraining(request.instanceId(), Instant.parse(request.heartbeatAt()));
        return Ack.ok();
    }

    @PostMapping("/media/active-after")
    ClusterStateProtocol.MediaInstancesResult activeMedia(
            @RequestBody ClusterStateProtocol.CutoffRequest request) {
        return new ClusterStateProtocol.MediaInstancesResult(mediaInstances.activeAfter(Instant.parse(request.cutoff()))
                .stream()
                .map(ClusterStateProtocol.MediaInstanceData::from)
                .toList());
    }

    @PostMapping("/media/remove-expired")
    ClusterStateProtocol.StringsResult removeExpiredMedia(
            @RequestBody ClusterStateProtocol.CutoffRequest request) {
        return new ClusterStateProtocol.StringsResult(
                mediaInstances.removeExpiredBefore(Instant.parse(request.cutoff())));
    }

    @PostMapping("/media/all")
    ClusterStateProtocol.MediaInstancesResult allMedia(@RequestBody Ack ignored) {
        return new ClusterStateProtocol.MediaInstancesResult(mediaInstances.all().stream()
                .map(ClusterStateProtocol.MediaInstanceData::from)
                .toList());
    }

    @PostMapping("/streams/register")
    ClusterStateProtocol.StreamRegistrationResult registerStream(
            @RequestBody ClusterStateProtocol.StreamRegistrationRequest request) {
        var candidate = request.candidate();
        StreamRegistry.Registration registration = streams.getOrRegister(
                candidate.streamKey().toDomain(), candidate::toDomain);
        return new ClusterStateProtocol.StreamRegistrationResult(
                ClusterStateProtocol.StreamEntryData.from(registration.entry()), registration.created());
    }

    @PostMapping("/streams/find")
    ClusterStateProtocol.StreamLookupResult findStream(
            @RequestBody ClusterStateProtocol.StreamKeyRequest request) {
        return new ClusterStateProtocol.StreamLookupResult(streams.find(request.streamKey().toDomain())
                .map(ClusterStateProtocol.StreamEntryData::from)
                .orElse(null));
    }

    @PostMapping("/streams/mark-live")
    ClusterStateProtocol.BooleanResult markStreamLive(
            @RequestBody ClusterStateProtocol.StreamKeyRequest request) {
        return new ClusterStateProtocol.BooleanResult(streams.markLive(request.streamKey().toDomain()));
    }

    @PostMapping("/streams/mark-dead")
    ClusterStateProtocol.BooleanResult markStreamDead(
            @RequestBody ClusterStateProtocol.StreamDeadRequest request) {
        return new ClusterStateProtocol.BooleanResult(
                streams.markDead(request.streamKey().toDomain(), request.reason()));
    }

    @PostMapping("/streams/add-subscriber")
    ClusterStateProtocol.IntResult addSubscriber(
            @RequestBody ClusterStateProtocol.StreamKeyRequest request) {
        return new ClusterStateProtocol.IntResult(streams.addSubscriber(request.streamKey().toDomain()));
    }

    @PostMapping("/streams/remove-subscriber")
    ClusterStateProtocol.IntResult removeSubscriber(
            @RequestBody ClusterStateProtocol.StreamKeyRequest request) {
        return new ClusterStateProtocol.IntResult(streams.removeSubscriber(request.streamKey().toDomain()));
    }

    @PostMapping("/streams/release")
    ClusterStateProtocol.IntResult releaseSubscription(
            @RequestBody ClusterStateProtocol.StreamKeyRequest request) {
        return new ClusterStateProtocol.IntResult(coordinator.release(request.streamKey().toDomain()));
    }

    @PostMapping("/streams/expire-pending")
    ClusterStateProtocol.StreamKeysResult expirePending(
            @RequestBody ClusterStateProtocol.PendingExpiryRequest request) {
        return new ClusterStateProtocol.StreamKeysResult(
                streams.expirePendingBefore(Instant.parse(request.cutoff()), request.reason()).stream()
                        .map(ClusterStateProtocol.StreamKeyData::from)
                        .toList());
    }

    @PostMapping("/streams/invalidate-instance")
    ClusterStateProtocol.IntResult invalidateMediaInstance(
            @RequestBody ClusterStateProtocol.InstanceInvalidationRequest request) {
        return new ClusterStateProtocol.IntResult(
                streams.invalidateMediaInstance(request.instanceId(), request.reason()));
    }

    @PostMapping("/streams/all")
    ClusterStateProtocol.StreamEntriesResult allStreams(@RequestBody Ack ignored) {
        return new ClusterStateProtocol.StreamEntriesResult(streams.entries().stream()
                .map(ClusterStateProtocol.StreamEntryData::from)
                .toList());
    }

    @PostMapping("/tokens/issue")
    ClusterStateProtocol.StringResult issueToken(
            @RequestBody ClusterStateProtocol.TokenIssueRequest request) {
        return new ClusterStateProtocol.StringResult(tokens.issue(
                request.streamKey().toDomain(), request.mediaInstanceId(),
                Duration.ofMillis(request.timeToLiveMillis())));
    }

    @PostMapping("/tokens/validate")
    ClusterStateProtocol.TokenValidationResponse validateToken(
            @RequestBody ClusterStateProtocol.TokenValidationRequest request) {
        return ClusterStateProtocol.TokenValidationResponse.from(tokens.validateAndConsume(
                request.token(), request.streamKey().toDomain(), request.mediaInstanceId()));
    }

    @PostMapping("/tokens/purge")
    ClusterStateProtocol.IntResult purgeTokens(@RequestBody Ack ignored) {
        return new ClusterStateProtocol.IntResult(tokens.purgeExpired());
    }
}
