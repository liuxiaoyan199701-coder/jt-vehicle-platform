package io.github.jtplatform.common.port;

import io.github.jtplatform.common.model.StreamKey;

/**
 * 「开流指令已下发，但等待窗口内没有码流到达」的观测出口。
 *
 * <p>刻意做成 jt-common 内的窄接口而不是直接依赖投递模块：流协调器不应知道事件怎么投递，
 * 未装配实现时保持 {@link #NONE}，零依赖启动的默认形态因而不受影响。
 */
@FunctionalInterface
public interface StreamNotArrivedListener {
    StreamNotArrivedListener NONE = (streamKey, mediaInstanceId, waitedMillis) -> { };

    /**
     * @param mediaInstanceId 本该收到码流的媒体节点，排查时要按它去查节点侧
     * @param waitedMillis 从登记该流到判定无流到达的实际等待时长
     */
    void onStreamNotArrived(StreamKey streamKey, String mediaInstanceId, long waitedMillis);
}
