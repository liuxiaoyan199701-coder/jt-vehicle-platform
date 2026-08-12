package io.github.jtplatform.media.frame;

import io.github.jtplatform.common.model.StreamKey;
import io.github.jtplatform.media.ingest.ReassembledPacket;
import io.github.jtplatform.media.protocol.Jt1078Constants;
import io.github.jtplatform.media.protocol.Jt1078Header;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class FrameAssembler {
    private final Map<ParameterKey, byte[]> parameterSets = new ConcurrentHashMap<>();

    public List<MediaFrame> assemble(ReassembledPacket packet) {
        Objects.requireNonNull(packet, "packet");
        Jt1078Header header = packet.header();
        StreamKey streamKey = packet.streamKey();
        byte[] payload = packet.payload();
        MediaCodec codec = codecFor(header.dataType(), header.payloadType());
        List<MediaFrame> frames = new ArrayList<>();

        if (codec == MediaCodec.H264 || codec == MediaCodec.H265) {
            for (NalUnit nalu : scanAnnexB(payload)) {
                MediaFrameType parameterType = parameterType(codec, nalu.type(codec));
                if (parameterType != null && updateParameterSet(streamKey, parameterType, nalu.data())) {
                    frames.add(new MediaFrame(streamKey, parameterType, codec, header.timestamp(), nalu.data()));
                }
            }
        } else if (codec == MediaCodec.AAC) {
            byte[] audioConfig = audioSpecificConfig(payload);
            if (audioConfig != null && updateParameterSet(streamKey, MediaFrameType.AUDIO_CONFIG, audioConfig)) {
                frames.add(new MediaFrame(streamKey, MediaFrameType.AUDIO_CONFIG,
                        codec, header.timestamp(), audioConfig));
            }
        }

        frames.add(new MediaFrame(streamKey, frameType(header.dataType()), codec,
                header.timestamp(), payload));
        return List.copyOf(frames);
    }

    public void clear(StreamKey streamKey) {
        parameterSets.keySet().removeIf(key -> key.streamKey().equals(streamKey));
    }

    private boolean updateParameterSet(StreamKey streamKey, MediaFrameType type, byte[] value) {
        ParameterKey key = new ParameterKey(streamKey, type);
        byte[] copy = Arrays.copyOf(value, value.length);
        byte[] previous = parameterSets.put(key, copy);
        return previous == null || !Arrays.equals(previous, copy);
    }

    private static MediaCodec codecFor(int dataType, int payloadType) {
        if (dataType <= Jt1078Constants.VIDEO_B_FRAME) {
            return switch (payloadType) {
                case Jt1078Constants.PT_H264 -> MediaCodec.H264;
                case Jt1078Constants.PT_H265 -> MediaCodec.H265;
                default -> MediaCodec.UNKNOWN;
            };
        }
        if (dataType == Jt1078Constants.AUDIO_FRAME) {
            return switch (payloadType) {
                case Jt1078Constants.PT_G711A -> MediaCodec.G711A;
                case Jt1078Constants.PT_AAC -> MediaCodec.AAC;
                default -> MediaCodec.UNKNOWN;
            };
        }
        return MediaCodec.TRANSPARENT;
    }

    private static MediaFrameType frameType(int dataType) {
        return switch (dataType) {
            case Jt1078Constants.VIDEO_I_FRAME -> MediaFrameType.VIDEO_KEY;
            case Jt1078Constants.VIDEO_P_FRAME -> MediaFrameType.VIDEO_DELTA;
            case Jt1078Constants.VIDEO_B_FRAME -> MediaFrameType.VIDEO_B;
            case Jt1078Constants.AUDIO_FRAME -> MediaFrameType.AUDIO;
            case Jt1078Constants.TRANSPARENT_DATA -> MediaFrameType.TRANSPARENT;
            default -> throw new IllegalArgumentException("Unsupported data type: " + dataType);
        };
    }

    private static MediaFrameType parameterType(MediaCodec codec, int nalType) {
        if (codec == MediaCodec.H264) {
            return switch (nalType) {
                case 7 -> MediaFrameType.SPS;
                case 8 -> MediaFrameType.PPS;
                default -> null;
            };
        }
        return switch (nalType) {
            case 32 -> MediaFrameType.VPS;
            case 33 -> MediaFrameType.SPS;
            case 34 -> MediaFrameType.PPS;
            default -> null;
        };
    }

    private static List<NalUnit> scanAnnexB(byte[] payload) {
        List<NalUnit> result = new ArrayList<>();
        int offset = findStartCode(payload, 0);
        while (offset >= 0) {
            int startCodeLength = startCodeLength(payload, offset);
            int next = findStartCode(payload, offset + startCodeLength);
            int end = next >= 0 ? next : payload.length;
            if (offset + startCodeLength < end) {
                result.add(new NalUnit(Arrays.copyOfRange(payload, offset, end), startCodeLength));
            }
            offset = next;
        }
        return result;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int index = Math.max(0, from); index + 2 < data.length; index++) {
            if (data[index] == 0 && data[index + 1] == 0
                    && (data[index + 2] == 1
                    || (index + 3 < data.length && data[index + 2] == 0 && data[index + 3] == 1))) {
                return index;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int offset) {
        return data[offset + 2] == 1 ? 3 : 4;
    }

    private static byte[] audioSpecificConfig(byte[] payload) {
        if (payload.length < 4 || (payload[0] & 0xff) != 0xff || (payload[1] & 0xf0) != 0xf0) {
            return null;
        }
        int objectType = ((payload[2] & 0xc0) >>> 6) + 1;
        int frequencyIndex = (payload[2] & 0x3c) >>> 2;
        int channelConfiguration = ((payload[2] & 0x01) << 2) | ((payload[3] & 0xc0) >>> 6);
        return new byte[] {
                (byte) ((objectType << 3) | (frequencyIndex >>> 1)),
                (byte) (((frequencyIndex & 1) << 7) | (channelConfiguration << 3))
        };
    }

    private record ParameterKey(StreamKey streamKey, MediaFrameType type) {}

    private record NalUnit(byte[] data, int startCodeLength) {
        private int type(MediaCodec codec) {
            int header = data[startCodeLength] & 0xff;
            return codec == MediaCodec.H264 ? header & 0x1f : (header >>> 1) & 0x3f;
        }
    }
}
