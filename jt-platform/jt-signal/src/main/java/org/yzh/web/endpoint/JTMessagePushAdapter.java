package org.yzh.web.endpoint;

import io.github.jtplatform.signal.delivery.SignalMessageDispatcher;
import io.github.yezhihao.netmc.session.Session;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.codec.JTMessageAdapter;
import org.yzh.protocol.codec.JTMessageDecoder;
import org.yzh.protocol.codec.JTMessageEncoder;
import org.yzh.protocol.commons.MessageId;
import org.yzh.protocol.t808.T0801;

public class JTMessagePushAdapter extends JTMessageAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(JTMessagePushAdapter.class);

    private final SignalMessageDispatcher dispatcher;

    public JTMessagePushAdapter(
            JTMessageEncoder messageEncoder,
            JTMessageDecoder messageDecoder,
            SignalMessageDispatcher dispatcher) {
        super(messageEncoder, messageDecoder);
        this.dispatcher = dispatcher;
    }

    @Override
    public void encodeLog(Session session, JTMessage message, ByteBuf output) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} >>> {} hex={}", session, MessageId.getName(message.getMessageId()),
                    ByteBufUtil.hexDump(output, 0, output.writerIndex()));
        }
    }

    @Override
    public void decodeLog(Session session, JTMessage message, ByteBuf input) {
        if (message == null) {
            return;
        }
        if (message instanceof T0801 media) {
            if (!message.isSubpackage() && media.getPacket() != null) {
                media.getPacket().retain();
            }
        } else {
            dispatcher.dispatch(session, message);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} <<< {} hex={}", session, MessageId.getName(message.getMessageId()),
                    ByteBufUtil.hexDump(input, 0, input.writerIndex()));
        }
    }
}
