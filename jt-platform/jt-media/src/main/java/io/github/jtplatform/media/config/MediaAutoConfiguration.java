package io.github.jtplatform.media.config;

import io.github.jtplatform.common.auth.StreamTokenStore;
import io.github.jtplatform.common.config.ReachableAddressResolver;
import io.github.jtplatform.common.port.MediaInstanceRegistry;
import io.github.jtplatform.common.port.StreamRegistry;
import io.github.jtplatform.common.port.StreamSubscriptionPort;
import io.github.jtplatform.common.service.StreamCoordinator;
import io.github.jtplatform.delivery.publisher.MessagePublisher;
import io.github.jtplatform.media.frame.FrameAssembler;
import io.github.jtplatform.media.ftp.RecordingFtpProperties;
import io.github.jtplatform.media.ftp.RecordingFtpServer;
import io.github.jtplatform.media.ftp.RecordingUploadFileStore;
import io.github.jtplatform.media.ftp.RecordingUploadPublisher;
import io.github.jtplatform.media.ftp.TemporaryFtpCredentialService;
import io.github.jtplatform.media.ingest.FragmentReassembler;
import io.github.jtplatform.media.lifecycle.MediaInstanceHeartbeatLifecycle;
import io.github.jtplatform.media.metrics.MediaNodeLoadMonitor;
import io.github.jtplatform.media.netty.MediaNodeServer;
import io.github.jtplatform.media.pipeline.FirstFrameListener;
import io.github.jtplatform.media.pipeline.MediaPipeline;
import io.github.jtplatform.media.recording.AlarmRecordingMessageListener;
import io.github.jtplatform.media.recording.RecordSink;
import io.github.jtplatform.media.recording.RecordingExportService;
import io.github.jtplatform.media.recording.RecordingMetadataPublisher;
import io.github.jtplatform.media.recording.RecordingPlaybackService;
import io.github.jtplatform.media.recording.RecordingRetentionLifecycle;
import io.github.jtplatform.media.recording.RecordingRetentionService;
import io.github.jtplatform.media.recording.RecordingSearchService;
import io.github.jtplatform.media.recording.RecordingSegmentListener;
import io.github.jtplatform.media.recording.RecordingStorageMetrics;
import io.github.jtplatform.media.sink.SinkRegistry;
import io.github.jtplatform.media.sink.WebSocketRawSink;
import io.github.jtplatform.media.talkback.TalkbackProperties;
import io.github.jtplatform.media.talkback.TalkbackService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties({
        MediaServerProperties.class,
        MediaRuntimeProperties.class,
        MediaAuthenticationProperties.class,
        RecordingProperties.class,
        RecordingFtpProperties.class,
        TalkbackProperties.class
})
public class MediaAutoConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    RecordingUploadFileStore recordingUploadFileStore(RecordingFtpProperties properties) {
        return new RecordingUploadFileStore(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "jt.media.ftp", name = "enabled", havingValue = "true")
    TemporaryFtpCredentialService temporaryFtpCredentialService(
            RecordingFtpProperties properties,
            MediaRuntimeProperties runtime,
            ReachableAddressResolver addressResolver,
            ObjectProvider<Clock> clockProvider) {
        String address = properties.getAdvertisedAddress();
        if (address == null || address.isBlank()) {
            address = addressResolver.resolve(runtime.getReachableAddress().toSettings());
            // 9206 下发地址与 PASV 响应必须一致；只改运行时解析值，不引入固定账号或固定密码。
            properties.setAdvertisedAddress(address);
        }
        return new TemporaryFtpCredentialService(
                properties, clockProvider.getIfAvailable(Clock::systemUTC), address);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "jt.media.ftp", name = "enabled", havingValue = "true")
    RecordingUploadPublisher recordingUploadPublisher(
            MessagePublisher publisher,
            RecordingFtpProperties properties,
            MediaRuntimeProperties runtime,
            ReachableAddressResolver addressResolver,
            ObjectProvider<Clock> clockProvider,
            @Value("${jt.instance.number:1}") int instanceNumber) {
        String address = properties.getAdvertisedAddress();
        if (address == null || address.isBlank()) {
            address = addressResolver.resolve(runtime.getReachableAddress().toSettings());
        }
        return new RecordingUploadPublisher(publisher, properties,
                clockProvider.getIfAvailable(Clock::systemUTC), runtime.getInstanceId(),
                address, io.github.jtplatform.common.model.MediaPorts.forInstance(instanceNumber).management());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "jt.media.ftp", name = "enabled", havingValue = "true")
    RecordingFtpServer recordingFtpServer(
            RecordingFtpProperties properties,
            TemporaryFtpCredentialService credentials,
            RecordingUploadPublisher publisher) {
        return new RecordingFtpServer(properties, credentials, publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    LegacyMediaPortValidator legacyMediaPortValidator(Environment environment) {
        return new LegacyMediaPortValidator(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    TalkbackService talkbackService(
            TalkbackProperties properties,
            ObjectProvider<Clock> clockProvider) {
        return new TalkbackService(properties, clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    WebSocketRawSink webSocketRawSink() {
        return new WebSocketRawSink();
    }

    @Bean
    @ConditionalOnMissingBean
    RecordingSegmentListener recordingSegmentListener(
            ObjectProvider<MessagePublisher> publisherProvider,
            ObjectProvider<Clock> clockProvider,
            @Value("${jt.media.instance-id:media-1}") String mediaInstanceId) {
        MessagePublisher publisher = publisherProvider.getIfAvailable();
        return publisher == null
                ? RecordingSegmentListener.noOp()
                : new RecordingMetadataPublisher(
                        publisher, mediaInstanceId, clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    RecordSink recordSink(
            RecordingProperties properties,
            RecordingSegmentListener segmentListener,
            ObjectProvider<Clock> clockProvider) {
        LOGGER.info("Media recording: realtime={}, playback={}, triggers=[manual={}, alarm={}, continuous={}]",
                properties.isRealtimeEnabled(), properties.isPlaybackEnabled(),
                properties.isManualEnabled(), properties.isAlarmEnabled(),
                properties.isContinuousEnabled());
        return new RecordSink(
                properties, segmentListener, clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    RecordingSearchService recordingSearchService(RecordingProperties properties) {
        return new RecordingSearchService(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    RecordingPlaybackService recordingPlaybackService(RecordingSearchService searchService) {
        return new RecordingPlaybackService(searchService);
    }

    @Bean
    @ConditionalOnMissingBean
    RecordingExportService recordingExportService(
            RecordingSearchService searchService,
            RecordingProperties properties) {
        return new RecordingExportService(searchService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    RecordingStorageMetrics recordingStorageMetrics(RecordingProperties properties) {
        return new RecordingStorageMetrics(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    RecordingRetentionService recordingRetentionService(
            RecordingProperties properties,
            ObjectProvider<Clock> clockProvider) {
        return new RecordingRetentionService(
                properties, clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    RecordingRetentionLifecycle recordingRetentionLifecycle(
            RecordingRetentionService retentionService,
            RecordingProperties properties) {
        return new RecordingRetentionLifecycle(retentionService, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    SinkRegistry mediaSinkRegistry(
            WebSocketRawSink webSocketRawSink,
            RecordSink recordSink,
            RecordingProperties recordingProperties) {
        SinkRegistry registry = new SinkRegistry();
        registry.register(webSocketRawSink);
        if (recordingProperties.enabled()) {
            registry.register(recordSink);
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    FrameAssembler mediaFrameAssembler() {
        return new FrameAssembler();
    }

    @Bean
    @ConditionalOnMissingBean
    FragmentReassembler mediaFragmentReassembler(
            MediaServerProperties properties, ObjectProvider<Clock> clockProvider) {
        return new FragmentReassembler(properties.getReassemblyTimeout(), properties.getMaxFrameBytes(),
                clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    FirstFrameListener mediaFirstFrameListener(
            ObjectProvider<StreamCoordinator> coordinatorProvider,
            ObjectProvider<StreamRegistry> streamRegistryProvider,
            @Value("${jt.media.instance-id:media-1}") String mediaInstanceId) {
        StreamCoordinator coordinator = coordinatorProvider.getIfAvailable();
        if (coordinator != null) {
            return streamKey -> coordinator.onFirstPacket(streamKey, mediaInstanceId);
        }
        StreamRegistry streams = streamRegistryProvider.getIfAvailable();
        if (streams == null) {
            return ignored -> false;
        }
        return streamKey -> streams.find(streamKey)
                .filter(entry -> entry.mediaInstanceId().equals(mediaInstanceId))
                .filter(entry -> entry.state() != io.github.jtplatform.common.model.StreamState.DEAD)
                .map(entry -> streams.markLive(streamKey)
                        || entry.state() == io.github.jtplatform.common.model.StreamState.LIVE)
                .orElse(false);
    }

    @Bean
    @ConditionalOnMissingBean
    MediaPipeline mediaPipeline(
            FragmentReassembler reassembler,
            FrameAssembler frameAssembler,
            SinkRegistry sinkRegistry,
            FirstFrameListener firstFrameListener) {
        return new MediaPipeline(reassembler, frameAssembler, sinkRegistry, firstFrameListener);
    }

    @Bean
    @ConditionalOnMissingBean
    AlarmRecordingMessageListener alarmRecordingMessageListener(
            RecordingProperties properties,
            RecordSink recordSink,
            MediaPipeline pipeline) {
        return new AlarmRecordingMessageListener(properties, recordSink, pipeline);
    }

    @Bean
    @ConditionalOnMissingBean
    MediaNodeLoadMonitor mediaNodeLoadMonitor(
            MediaPipeline pipeline,
            WebSocketRawSink webSocketRawSink,
            ObjectProvider<Clock> clockProvider) {
        return new MediaNodeLoadMonitor(
                pipeline::activeStreamCount,
                webSocketRawSink::outboundBytes,
                clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "jt.media.server", name = "enabled", havingValue = "true", matchIfMissing = true)
    MediaNodeServer mediaNodeServer(
            MediaServerProperties properties,
            LegacyMediaPortValidator portValidator,
            MediaPipeline pipeline,
            WebSocketRawSink webSocketRawSink,
            MediaAuthenticationProperties authentication,
            ObjectProvider<StreamTokenStore> tokenStoreProvider,
            ObjectProvider<StreamRegistry> streamRegistryProvider,
            ObjectProvider<StreamSubscriptionPort> subscriptionPortProvider,
            MediaNodeLoadMonitor loadMonitor,
            MediaRuntimeProperties runtimeProperties,
            RecordingStorageMetrics recordingStorageMetrics,
            RecordingUploadFileStore recordingUploadFileStore,
            RecordSink recordSink,
            TalkbackService talkbackService,
            RecordingPlaybackService recordingPlaybackService,
            @Value("${jt.instance.number:1}") int instanceNumber,
            @Value("${jt.media.instance-id:media-1}") String mediaInstanceId) {
        authentication.validate();
        StreamTokenStore tokenStore = tokenStoreProvider.getIfAvailable();
        if (authentication.isEnabled() && tokenStore == null) {
            throw new IllegalStateException(
                    "A StreamTokenStore is required when jt.auth.stream.mode=jwt");
        }
        return new MediaNodeServer(
                properties,
                portValidator,
                pipeline,
                webSocketRawSink,
                instanceNumber,
                authentication.isEnabled(),
                tokenStore,
                mediaInstanceId,
                streamRegistryProvider.getIfAvailable(),
                loadMonitor,
                runtimeProperties.getCapacity(),
                recordingStorageMetrics,
                recordingUploadFileStore,
                recordSink,
                talkbackService,
                recordingPlaybackService,
                subscriptionPortProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
            MediaNodeServer.class,
            MediaInstanceRegistry.class,
            StreamRegistry.class,
            ReachableAddressResolver.class,
            Clock.class
    })
    MediaInstanceHeartbeatLifecycle mediaInstanceHeartbeatLifecycle(
            MediaNodeServer server,
            MediaInstanceRegistry instances,
            StreamRegistry streams,
            MediaNodeLoadMonitor loadMonitor,
            MediaRuntimeProperties properties,
            ReachableAddressResolver addressResolver,
            Clock clock) {
        return new MediaInstanceHeartbeatLifecycle(
                instances,
                streams,
                loadMonitor,
                properties,
                addressResolver,
                clock,
                server::isRunning,
                server::ports);
    }
}
