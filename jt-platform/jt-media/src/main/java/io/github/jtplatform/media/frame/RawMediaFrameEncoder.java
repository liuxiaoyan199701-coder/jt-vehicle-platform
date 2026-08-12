package io.github.jtplatform.media.frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

public final class RawMediaFrameEncoder {
    public static final int HEADER_LENGTH = 8;

    private static final byte[] MAGIC = {'J', 'T', '7', '8'};

    private RawMediaFrameEncoder() {
    }

    public static byte[] encode(MediaFrame frame) {
        Objects.requireNonNull(frame, "frame");
        byte[] payload = frame.payload();
        ByteBuffer encoded = ByteBuffer.allocate(HEADER_LENGTH + payload.length)
                .order(ByteOrder.BIG_ENDIAN);
        encoded.put(MAGIC);
        encoded.put((byte) frame.type().wireValue());
        encoded.put((byte) frame.streamKey().channel());
        encoded.putShort((short) 0);
        encoded.put(payload);
        return encoded.array();
    }
}
