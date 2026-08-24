package io.github.jtplatform.signal.messagelog;

import io.github.yezhihao.netmc.session.Session;
import io.netty.buffer.ByteBuf;
import org.yzh.protocol.basics.JTMessage;

/**
 * 报文级日志的采集出口：把「原始帧字节 + 解析结果」送进投递管道。
 *
 * <p>做成接口是为了让「没装配投递器」和「配置关掉采集」这两种情况都退化成 {@link #NONE}，
 * 而不是在编解码收口里到处判空——那两个方法跑在 Netty worker 线程上，
 * 每多一个分支都是主链路的成本。
 *
 * <p>所有实现 MUST 自行吞掉异常：日志采集失败绝不能让一帧报文收发不出去。
 */
public interface MessageLogEmitter {

    MessageLogEmitter NONE = new MessageLogEmitter() {
        @Override
        public void inbound(Session session, JTMessage message, ByteBuf input) {
        }

        @Override
        public void outbound(Session session, JTMessage message, ByteBuf output) {
        }

        @Override
        public void decodeFailure(Session session, ByteBuf input, Throwable failure) {
        }
    };

    /**
     * 上行报文。
     *
     * @param input 本帧的原始字节，返回后即被释放，实现必须在方法内同步拷贝
     */
    void inbound(Session session, JTMessage message, ByteBuf input);

    /**
     * 下行报文。
     *
     * @param output 完整成帧字节（含 0x7e 定界、转义与校验码）；分包时是 CompositeByteBuf
     */
    void outbound(Session session, JTMessage message, ByteBuf output);

    /**
     * 解码失败的帧。没有解析结果，但原始字节恰恰是这时最有排障价值的东西。
     */
    void decodeFailure(Session session, ByteBuf input, Throwable failure);
}
