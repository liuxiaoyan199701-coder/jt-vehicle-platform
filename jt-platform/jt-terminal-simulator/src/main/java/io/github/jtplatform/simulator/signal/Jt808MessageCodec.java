package io.github.jtplatform.simulator.signal;

import io.github.yezhihao.protostar.SchemaManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.util.Objects;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.codec.JTMessageDecoder;
import org.yzh.protocol.codec.JTMessageEncoder;

public final class Jt808MessageCodec {
    private final JTMessageEncoder encoder;
    private final JTMessageDecoder decoder;

    public Jt808MessageCodec() {
        SchemaManager schemas = new SchemaManager("org.yzh.protocol");
        this.encoder = new JTMessageEncoder(schemas);
        this.decoder = new JTMessageDecoder(schemas);
    }

    public byte[] encode(JTMessage message) {
        Objects.requireNonNull(message, "message");
        ByteBuf encoded = encoder.encode(message);
        try {
            return ByteBufUtil.getBytes(encoded);
        } finally {
            ReferenceCountUtil.safeRelease(encoded);
        }
    }

    public JTMessage decode(byte[] frame) throws InvalidJt808FrameException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < 8 || frame[0] != 0x7e || frame[frame.length - 1] != 0x7e) {
            throw new InvalidJt808FrameException("JT/T 808 frame must contain two delimiters and a header");
        }
        ByteBuf input = Unpooled.wrappedBuffer(frame);
        try {
            return decoder.decode(input);
        } catch (IndexOutOfBoundsException | IllegalArgumentException malformed) {
            throw new InvalidJt808FrameException("Unable to decode JT/T 808 frame", malformed);
        } finally {
            ReferenceCountUtil.safeRelease(input);
        }
    }
}
