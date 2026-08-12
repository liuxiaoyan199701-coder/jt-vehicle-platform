package io.github.jtplatform.boot.cluster;

import io.github.jtplatform.common.auth.TokenValidationResult;
import io.github.jtplatform.common.model.MediaInstance;
import io.github.jtplatform.common.model.MediaPorts;
import io.github.jtplatform.common.model.MediaTarget;
import io.github.jtplatform.common.model.StreamEntry;
import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.model.StreamState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

public final class ClusterStateProtocol {
    private ClusterStateProtocol() {
    }

    public record Ack(boolean accepted) {
        public static Ack ok() {
            return new Ack(true);
        }
    }

    public record BooleanResult(boolean value) {
    }

    public record IntResult(int value) {
    }

    public record StringResult(String value) {
    }

    public record StringsResult(List<String> values) {
    }

    public record StreamKeyData(String deviceId, int channel, String streamKind) {
        public static StreamKeyData from(StreamKey key) {
            return new StreamKeyData(key.deviceId(), key.channel(), key.streamKind().wireValue());
        }

        public StreamKey toDomain() {
            return new StreamKey(deviceId, channel, StreamKind.fromWireValue(streamKind));
        }
    }

    public record MediaTargetData(
            String instanceId,
            String reachableAddress,
            int tcpPort,
            int udpPort,
            int websocketPort) {
        public static MediaTargetData from(MediaTarget target) {
            return new MediaTargetData(target.instanceId(), target.reachableAddress(), target.tcpPort(),
                    target.udpPort(), target.websocketPort());
        }

        public MediaTarget toDomain() {
            return new MediaTarget(instanceId, reachableAddress, tcpPort, udpPort, websocketPort);
        }
    }

    public record MediaInstanceData(
            String instanceId,
            String reachableAddress,
            int managementPort,
            int mainPort,
            int subPort,
            int playbackPort,
            int talkbackPort,
            int websocketPort,
            int httpFlvPort,
            int maxStreams,
            long maxOutboundBitsPerSecond,
            int currentStreams,
            long outboundBitsPerSecond,
            String heartbeatAt,
            boolean draining) {
        public static MediaInstanceData from(MediaInstance instance) {
            MediaPorts ports = instance.ports();
            return new MediaInstanceData(
                    instance.instanceId(), instance.reachableAddress(), ports.management(), ports.main(),
                    ports.sub(), ports.playback(), ports.talkback(), ports.websocket(), ports.httpFlv(),
                    instance.maxStreams(), instance.maxOutboundBitsPerSecond(), instance.currentStreams(),
                    instance.outboundBitsPerSecond(), instance.heartbeatAt().toString(), instance.draining());
        }

        public MediaInstance toDomain() {
            return new MediaInstance(
                    instanceId,
                    reachableAddress,
                    new MediaPorts(managementPort, mainPort, subPort, playbackPort, talkbackPort,
                            websocketPort, httpFlvPort),
                    maxStreams,
                    maxOutboundBitsPerSecond,
                    currentStreams,
                    outboundBitsPerSecond,
                    Instant.parse(heartbeatAt),
                    draining);
        }
    }

    public record StreamEntryData(
            StreamKeyData streamKey,
            String streamId,
            String mediaInstanceId,
            MediaTargetData mediaTarget,
            String state,
            int subscriberCount,
            String createdAt,
            String lastActiveAt,
            String terminalReason) {
        public static StreamEntryData from(StreamEntry entry) {
            return new StreamEntryData(
                    StreamKeyData.from(entry.streamKey()),
                    entry.streamId(),
                    entry.mediaInstanceId(),
                    MediaTargetData.from(entry.mediaTarget()),
                    entry.state().name(),
                    entry.subscriberCount(),
                    entry.createdAt().toString(),
                    entry.lastActiveAt().toString(),
                    entry.terminalReason());
        }

        public StreamEntry toDomain() {
            Instant creationTime = Instant.parse(createdAt);
            StreamEntry entry = new StreamEntry(
                    streamKey.toDomain(),
                    streamId,
                    mediaInstanceId,
                    mediaTarget.toDomain(),
                    Clock.fixed(creationTime, ZoneOffset.UTC));
            for (int index = 0; index < subscriberCount; index++) {
                entry.subscribe();
            }
            StreamState currentState = StreamState.valueOf(state);
            if (currentState == StreamState.LIVE) {
                entry.markLive();
            } else if (currentState == StreamState.DEAD) {
                entry.markDead(terminalReason == null || terminalReason.isBlank()
                        ? "stream is dead"
                        : terminalReason);
            }
            return entry;
        }
    }

    public record InstanceIdRequest(String instanceId) {
    }

    public record MediaLoadRequest(
            String instanceId,
            int currentStreams,
            long outboundBitsPerSecond,
            String heartbeatAt) {
    }

    public record MediaHeartbeatRequest(String instanceId, String heartbeatAt) {
    }

    public record CutoffRequest(String cutoff) {
    }

    public record MediaLookupResult(MediaInstanceData instance) {
    }

    public record MediaInstancesResult(List<MediaInstanceData> instances) {
    }

    public record StreamKeyRequest(StreamKeyData streamKey) {
    }

    public record StreamRegistrationRequest(StreamEntryData candidate) {
    }

    public record StreamRegistrationResult(StreamEntryData entry, boolean created) {
    }

    public record StreamLookupResult(StreamEntryData entry) {
    }

    public record StreamDeadRequest(StreamKeyData streamKey, String reason) {
    }

    public record PendingExpiryRequest(String cutoff, String reason) {
    }

    public record InstanceInvalidationRequest(String instanceId, String reason) {
    }

    public record StreamKeysResult(List<StreamKeyData> streamKeys) {
    }

    public record StreamEntriesResult(List<StreamEntryData> entries) {
    }

    public record TokenIssueRequest(StreamKeyData streamKey, String mediaInstanceId, long timeToLiveMillis) {
    }

    public record TokenValidationRequest(String token, StreamKeyData streamKey, String mediaInstanceId) {
    }

    public record TokenValidationResponse(String result) {
        public static TokenValidationResponse from(TokenValidationResult result) {
            return new TokenValidationResponse(result.name());
        }

        public TokenValidationResult toDomain() {
            return TokenValidationResult.valueOf(result);
        }
    }
}
