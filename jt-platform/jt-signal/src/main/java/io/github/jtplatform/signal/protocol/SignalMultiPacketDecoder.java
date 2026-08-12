package io.github.jtplatform.signal.protocol;

import io.github.yezhihao.protostar.SchemaManager;
import io.github.yezhihao.protostar.util.Explain;
import io.netty.buffer.ByteBuf;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.codec.MultiPacketDecoder;
import org.yzh.protocol.codec.MultiPacketListener;

/** Suppresses incomplete subpackets until the complete message has been reassembled. */
public final class SignalMultiPacketDecoder extends MultiPacketDecoder {
    private final ThreadLocal<Boolean> incomplete = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public SignalMultiPacketDecoder(SchemaManager schemaManager, MultiPacketListener listener) {
        super(schemaManager, listener);
    }

    @Override
    public JTMessage decode(ByteBuf input, Explain explain) {
        incomplete.set(Boolean.FALSE);
        try {
            JTMessage message = super.decode(input, explain);
            return incomplete.get() ? null : message;
        } finally {
            incomplete.remove();
        }
    }

    @Override
    protected ByteBuf[] addAndGet(JTMessage message, ByteBuf packetData) {
        ByteBuf[] packets = super.addAndGet(message, packetData);
        incomplete.set(packets == null);
        return packets;
    }
}
