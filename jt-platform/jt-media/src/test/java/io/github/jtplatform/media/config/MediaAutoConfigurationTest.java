package io.github.jtplatform.media.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.common.service.StreamCoordinator;
import io.github.jtplatform.media.pipeline.FirstFrameListener;
import io.github.jtplatform.media.pipeline.MediaPipeline;
import io.github.jtplatform.media.recording.AlarmRecordingMessageListener;
import io.github.jtplatform.media.recording.RecordSink;
import io.github.jtplatform.media.recording.RecordingExportService;
import io.github.jtplatform.media.recording.RecordingPlaybackService;
import io.github.jtplatform.media.recording.RecordingRetentionLifecycle;
import io.github.jtplatform.media.recording.RecordingRetentionService;
import io.github.jtplatform.media.recording.RecordingSearchService;
import io.github.jtplatform.media.recording.RecordingSegmentListener;
import io.github.jtplatform.media.recording.RecordingStorageMetrics;
import io.github.jtplatform.media.sink.SinkRegistry;
import io.github.jtplatform.media.sink.WebSocketRawSink;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class MediaAutoConfigurationTest {
    @TempDir
    Path temporaryDirectory;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MediaAutoConfiguration.class)
            .withPropertyValues("jt.media.server.enabled=false");

    @Test
    void wiresFourStagePipelineAndDefaultWebSocketSink() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MediaPipeline.class);
            assertThat(context).hasSingleBean(WebSocketRawSink.class);
            assertThat(context).hasSingleBean(RecordSink.class);
            assertThat(context).hasSingleBean(AlarmRecordingMessageListener.class);
            assertThat(context).hasSingleBean(RecordingSegmentListener.class);
            assertThat(context).hasSingleBean(RecordingSearchService.class);
            assertThat(context).hasSingleBean(RecordingPlaybackService.class);
            assertThat(context).hasSingleBean(RecordingExportService.class);
            assertThat(context).hasSingleBean(RecordingRetentionService.class);
            assertThat(context).hasSingleBean(RecordingRetentionLifecycle.class);
            assertThat(context).hasSingleBean(RecordingStorageMetrics.class);
            assertThat(context).hasSingleBean(SinkRegistry.class);
            assertThat(context.getBean(SinkRegistry.class).registeredSinks())
                    .containsExactly(context.getBean(WebSocketRawSink.class));
            assertThat(context.getBean(RecordingProperties.class).getSegmentDuration())
                    .isEqualTo(Duration.ofSeconds(30));
            assertThat(context.getBean(RecordingProperties.class).getSearchMergeTolerance())
                    .isEqualTo(Duration.ofSeconds(1));
            assertThat(context.getBean(RecordingProperties.class).getExportRoot())
                    .isEqualTo(Path.of("recording-exports"));
            assertThat(context.getBean(RecordingProperties.class).getFfmpegCommand())
                    .isEqualTo("ffmpeg");
            assertThat(context.getBean(RecordingProperties.class).getExportConcurrency())
                    .isEqualTo(2);
            assertThat(context.getBean(RecordingProperties.class).getExportQueueCapacity())
                    .isEqualTo(16);
            assertThat(context.getBean(RecordingProperties.class).getExportTimeout())
                    .isEqualTo(Duration.ofMinutes(30));
            assertThat(context.getBean(RecordingProperties.class).retentionEnabled()).isFalse();
            assertThat(context.getBean(RecordingProperties.class).isManualEnabled()).isFalse();
            assertThat(context.getBean(RecordingProperties.class).isAlarmEnabled()).isFalse();
            assertThat(context.getBean(RecordingProperties.class).isContinuousEnabled()).isTrue();
        });
    }

    @Test
    void registersRecordSinkWhenEitherSwitchIsEnabledAndLogsBothStates(CapturedOutput output) {
        contextRunner
                .withPropertyValues(
                        "jt.media.recording.realtime-enabled=false",
                        "jt.media.recording.playback-enabled=true",
                        "jt.media.recording.manual-enabled=true",
                        "jt.media.recording.alarm-enabled=true",
                        "jt.media.recording.continuous-enabled=false",
                        "jt.media.recording.root=" + temporaryDirectory.resolve("recordings"),
                        "jt.media.recording.segment-duration=12s",
                        "jt.media.recording.retention-days=2",
                        "jt.media.recording.max-bytes=1234",
                        "jt.media.recording.retention-check-interval=10m",
                        "jt.media.recording.export-root=" + temporaryDirectory.resolve("exports"),
                        "jt.media.recording.ffmpeg-command=ffmpeg-custom",
                        "jt.media.recording.export-concurrency=3",
                        "jt.media.recording.export-queue-capacity=9",
                        "jt.media.recording.export-timeout=4m")
                .run(context -> {
                    RecordingProperties properties = context.getBean(RecordingProperties.class);
                    assertThat(properties.isRealtimeEnabled()).isFalse();
                    assertThat(properties.isPlaybackEnabled()).isTrue();
                    assertThat(properties.isManualEnabled()).isTrue();
                    assertThat(properties.isAlarmEnabled()).isTrue();
                    assertThat(properties.isContinuousEnabled()).isFalse();
                    assertThat(properties.getSegmentDuration()).isEqualTo(Duration.ofSeconds(12));
                    assertThat(properties.getRetentionDays()).isEqualTo(2);
                    assertThat(properties.getMaxBytes()).isEqualTo(1234);
                    assertThat(properties.getRetentionCheckInterval()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.getExportRoot())
                            .isEqualTo(temporaryDirectory.resolve("exports"));
                    assertThat(properties.getFfmpegCommand()).isEqualTo("ffmpeg-custom");
                    assertThat(properties.getExportConcurrency()).isEqualTo(3);
                    assertThat(properties.getExportQueueCapacity()).isEqualTo(9);
                    assertThat(properties.getExportTimeout()).isEqualTo(Duration.ofMinutes(4));
                    assertThat(context.getBean(SinkRegistry.class).registeredSinks())
                            .containsExactly(
                                    context.getBean(WebSocketRawSink.class),
                                    context.getBean(RecordSink.class));
                });

        assertThat(output).contains("Media recording: realtime=false, playback=true, "
                + "triggers=[manual=true, alarm=true, continuous=false]");
    }

    @Test
    void firstFrameListenerReportsTheOwningMediaInstance() {
        StreamCoordinator coordinator = mock(StreamCoordinator.class);
        StreamKey streamKey = new StreamKey("13800138000", 1, StreamKind.MAIN);

        contextRunner
                .withBean(StreamCoordinator.class, () -> coordinator)
                .withPropertyValues("jt.media.instance-id=media-7")
                .run(context -> {
                    context.getBean(FirstFrameListener.class).onFirstFrame(streamKey);

                    verify(coordinator).onFirstPacket(streamKey, "media-7");
                });
    }
}
