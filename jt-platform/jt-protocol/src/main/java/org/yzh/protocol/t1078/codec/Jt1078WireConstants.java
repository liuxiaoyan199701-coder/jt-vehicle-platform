package org.yzh.protocol.t1078.codec;

public final class Jt1078WireConstants {
    public static final int MAGIC = 0x30316364;
    public static final int FIXED_RTP_HEADER = 0x81;
    public static final int MARKER_MASK = 0x80;

    public static final int TRANSPARENT_HEADER_LENGTH = 18;
    public static final int AUDIO_HEADER_LENGTH = 26;
    public static final int VIDEO_HEADER_LENGTH = 30;
    public static final int MAX_PAYLOAD_LENGTH = 0xffff;

    public static final int VIDEO_I_FRAME = 0;
    public static final int VIDEO_P_FRAME = 1;
    public static final int VIDEO_B_FRAME = 2;
    public static final int AUDIO_FRAME = 3;
    public static final int TRANSPARENT_DATA = 4;

    public static final int PT_H264 = 98;
    public static final int PT_H265 = 99;
    public static final int PT_G711A = 6;
    public static final int PT_G726 = 8;
    public static final int PT_AAC = 19;
    public static final int PT_PCM = 22;

    private Jt1078WireConstants() {
    }
}
