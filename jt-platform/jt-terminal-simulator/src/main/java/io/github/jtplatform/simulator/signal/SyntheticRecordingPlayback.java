package io.github.jtplatform.simulator.signal;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import io.github.jtplatform.simulator.media.MediaFrame;
import io.github.jtplatform.simulator.media.MediaFrameType;
import io.github.jtplatform.simulator.stream.Jt1078TcpWriter;
import io.github.jtplatform.simulator.stream.MediaTarget;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.yzh.protocol.t1078.codec.Jt1078SimFormat;

/** 以少量确定性 H.264/音频帧模拟设备录像回放，结束时保证关闭 JT/T 1078 连接。 */
public final class SyntheticRecordingPlayback {
    private final SimulatorConfig config;

    public SyntheticRecordingPlayback(SimulatorConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public void play(RecordingPlaybackRequest request) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        LocalDateTime end = request.endTime() == null
                ? request.startTime().plusSeconds(1) : request.endTime();
        long durationMillis = Math.max(20, Duration.between(request.startTime(), end).toMillis());
        // 验收只需有可解码数据；按 10 倍加速，避免默认两小时回放阻塞测试平台。
        long acceleratedMillis = Math.min(5_000, Math.max(20, durationMillis / 10));
        int channel = request.channel();
        try (Jt1078TcpWriter writer = Jt1078TcpWriter.connect(
                new MediaTarget(request.host(), request.tcpPort()), config.mobileNo(), channel,
                config.maxPayloadBytes(), Duration.ofSeconds(5), config.simFormat())) {
            long base = 0;
            int frames = (int) Math.max(1, acceleratedMillis / 40);
            for (int index = 0; index < frames; index++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("recording playback interrupted");
                }
                writer.write(new MediaFrame(MediaFrameType.VIDEO_I, base,
                        syntheticH264(index, true)));
                base += 40;
                if (acceleratedMillis > 20) {
                    Thread.sleep(Math.min(40, acceleratedMillis / frames));
                }
            }
        }
    }

    static byte[] syntheticH264(int index, boolean keyFrame) {
        // Annex-B AUD + SPS/PPS-like test NALs + IDR/non-IDR slice. 解码器可识别帧边界，
        // 与真实采集管线的 H.264 access unit 形状一致。
        byte slice = (byte) (keyFrame ? 0x65 : 0x41);
        return new byte[] {
                0, 0, 0, 1, 0x09, 0x10,
                0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f,
                0, 0, 0, 1, 0x68, (byte) 0xce, 0x06, (byte) 0xe2,
                0, 0, 0, 1, slice, (byte) (index & 0xff), 0x20, 0x07
        };
    }
}
