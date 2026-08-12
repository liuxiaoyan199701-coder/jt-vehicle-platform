package io.github.jtplatform.media.frame;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.common.model.StreamKind;
import io.github.jtplatform.media.ingest.ReassembledPacket;
import io.github.jtplatform.media.protocol.FragmentFlag;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.github.jtplatform.media.protocol.Jt1078Header;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameAssemblerTest {
    private static final StreamKey STREAM = new StreamKey("13800138000", 1, StreamKind.MAIN);

    private final FrameAssembler assembler = new FrameAssembler();

    @Test
    void extractsAndCachesH264ParameterSetsBeforeKeyFrame() {
        byte[] payload = {
                0, 0, 0, 1, 0x67, 0x64, 0, 0x1f,
                0, 0, 1, 0x68, (byte) 0xee,
                0, 0, 1, 0x65, 1, 2, 3
        };

        List<MediaFrame> first = assembler.assemble(packet(
                Jt1078Constants.VIDEO_I_FRAME, Jt1078Constants.PT_H264, payload));
        List<MediaFrame> repeated = assembler.assemble(packet(
                Jt1078Constants.VIDEO_I_FRAME, Jt1078Constants.PT_H264, payload));

        assertEquals(List.of(MediaFrameType.SPS, MediaFrameType.PPS, MediaFrameType.VIDEO_KEY),
                first.stream().map(MediaFrame::type).toList());
        assertEquals(MediaCodec.H264, first.getLast().codec());
        assertEquals(List.of(MediaFrameType.VIDEO_KEY), repeated.stream().map(MediaFrame::type).toList());
    }

    @Test
    void recognizesH265VpsSpsAndPps() {
        byte[] payload = {
                0, 0, 1, 0x40, 1,
                0, 0, 1, 0x42, 1,
                0, 0, 1, 0x44, 1,
                0, 0, 1, 0x26, 1
        };

        List<MediaFrame> frames = assembler.assemble(packet(
                Jt1078Constants.VIDEO_I_FRAME, Jt1078Constants.PT_H265, payload));

        assertEquals(List.of(MediaFrameType.VPS, MediaFrameType.SPS, MediaFrameType.PPS,
                        MediaFrameType.VIDEO_KEY),
                frames.stream().map(MediaFrame::type).toList());
        assertEquals(MediaCodec.H265, frames.getLast().codec());
    }

    @Test
    void derivesAacAudioSpecificConfigFromAdts() {
        byte[] adts = {(byte) 0xff, (byte) 0xf1, 0x50, (byte) 0x80, 0, 0, 0};

        List<MediaFrame> frames = assembler.assemble(packet(
                Jt1078Constants.AUDIO_FRAME, Jt1078Constants.PT_AAC, adts));

        assertEquals(List.of(MediaFrameType.AUDIO_CONFIG, MediaFrameType.AUDIO),
                frames.stream().map(MediaFrame::type).toList());
        assertArrayEquals(new byte[] {0x12, 0x10}, frames.getFirst().payload());
    }

    @Test
    void mapsStandardAudioPayloadTypes() {
        assertEquals(6, Jt1078Constants.PT_G711A);
        assertEquals(8, Jt1078Constants.PT_G726);
        assertEquals(19, Jt1078Constants.PT_AAC);
        assertEquals(22, Jt1078Constants.PT_PCM);

        assertEquals(MediaCodec.G711A, audioCodec(6));
        assertEquals(MediaCodec.UNKNOWN, audioCodec(8));
        assertEquals(MediaCodec.AAC, audioCodec(19));
        assertEquals(MediaCodec.UNKNOWN, audioCodec(22));
    }

    private MediaCodec audioCodec(int payloadType) {
        return assembler.assemble(packet(
                Jt1078Constants.AUDIO_FRAME, payloadType, new byte[] {0})).getLast().codec();
    }

    private static ReassembledPacket packet(int dataType, int payloadType, byte[] payload) {
        return new ReassembledPacket(STREAM, new Jt1078Header(
                0, payloadType, 1, STREAM.deviceId(), STREAM.channel(), dataType,
                FragmentFlag.ATOMIC, 10, 0, 0, payload.length), payload);
    }
}
