package io.github.jtplatform.media.talkback;

import io.github.jtplatform.common.model.StreamKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.time.Clock;
import java.util.Objects;
import org.yzh.protocol.t1078.codec.Jt1078FragmentFlag;
import org.yzh.protocol.t1078.codec.Jt1078PacketEncoder;
import org.yzh.protocol.t1078.codec.Jt1078PacketHeader;
import org.yzh.protocol.t1078.codec.Jt1078WireConstants;

final class TalkbackPacketEncoder {
    private static final Jt1078PacketEncoder ENCODER = new Jt1078PacketEncoder();

    private TalkbackPacketEncoder() {
    }

    static ByteBuf encode(
            StreamKey streamKey,
            byte[] audio,
            int payloadType,
            int sequence,
            Clock clock) {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(audio, "audio");
        Objects.requireNonNull(clock, "clock");
        long timestamp = clock.millis();
        Jt1078PacketHeader header = new Jt1078PacketHeader(
                payloadType,
                true,
                sequence,
                streamKey.deviceId(),
                streamKey.channel(),
                Jt1078WireConstants.AUDIO_FRAME,
                Jt1078FragmentFlag.ATOMIC,
                timestamp,
                0,
                0);
        return Unpooled.wrappedBuffer(ENCODER.encode(header, audio));
    }
}
