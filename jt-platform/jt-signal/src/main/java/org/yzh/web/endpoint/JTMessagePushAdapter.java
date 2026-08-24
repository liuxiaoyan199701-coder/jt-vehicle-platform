package org.yzh.web.endpoint;

import io.github.jtplatform.signal.delivery.SignalMessageDispatcher;
import io.github.jtplatform.signal.messagelog.MessageLogEmitter;
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
import org.yzh.protocol.t1078.T9206;
import org.yzh.protocol.t808.T0801;

public class JTMessagePushAdapter extends JTMessageAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(JTMessagePushAdapter.class);

    private final SignalMessageDispatcher dispatcher;
    private final MessageLogEmitter messageLog;

    public JTMessagePushAdapter(
            JTMessageEncoder messageEncoder,
            JTMessageDecoder messageDecoder,
            SignalMessageDispatcher dispatcher) {
        this(messageEncoder, messageDecoder, dispatcher, MessageLogEmitter.NONE);
    }

    public JTMessagePushAdapter(
            JTMessageEncoder messageEncoder,
            JTMessageDecoder messageDecoder,
            SignalMessageDispatcher dispatcher,
            MessageLogEmitter messageLog) {
        super(messageEncoder, messageDecoder);
        this.dispatcher = dispatcher;
        this.messageLog = messageLog == null ? MessageLogEmitter.NONE : messageLog;
    }

    /**
     * 解码失败的帧走不到 {@link #decodeLog}——异常在 {@code super.decode} 里就抛出来了。
     *
     * <p>而畸形帧恰恰是最需要留证的一类：只有原始字节能说明终端到底发了什么。这里补一次截获后
     * 原样抛出，不改变任何既有的异常处理路径。
     */
    @Override
    public JTMessage decode(ByteBuf input, Session session) {
        try {
            return super.decode(input, session);
        } catch (RuntimeException failure) {
            messageLog.decodeFailure(session, input, failure);
            throw failure;
        }
    }

    @Override
    public void encodeLog(Session session, JTMessage message, ByteBuf output) {
        messageLog.outbound(session, message, output);
        if (LOGGER.isDebugEnabled()) {
            if (message instanceof T9206) {
                // 9206 正文含一次性 FTP 用户名和密码，禁止以对象或原始 hex 形式进入日志。
                LOGGER.debug("{} >>> {} payload=<redacted>",
                        session, MessageId.getName(message.getMessageId()));
            } else {
                LOGGER.debug("{} >>> {} hex={}", session, MessageId.getName(message.getMessageId()),
                        ByteBufUtil.hexDump(output, 0, output.writerIndex()));
            }
        }
    }

    @Override
    public void decodeLog(Session session, JTMessage message, ByteBuf input) {
        if (message == null) {
            // 分包的中间帧。组包完成后的整条消息才是有排障价值的单元，中间帧入库只会刷屏。
            return;
        }
        // 先留证再分发：业务分发抛异常时，这一帧的原始字节已经在路上了。
        messageLog.inbound(session, message, input);
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
