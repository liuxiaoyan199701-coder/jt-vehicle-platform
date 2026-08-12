package io.github.jtplatform.media.protocol;

public final class Jt1078Constants {
    public static final byte[] MAGIC = {0x30, 0x31, 0x63, 0x64};
    public static final int COMMON_HEADER_LENGTH = 16;
    public static final int TRANSPARENT_HEADER_LENGTH = 18;
    public static final int AUDIO_HEADER_LENGTH = 26;
    public static final int VIDEO_HEADER_LENGTH = 30;

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

    private Jt1078Constants() {}
}
