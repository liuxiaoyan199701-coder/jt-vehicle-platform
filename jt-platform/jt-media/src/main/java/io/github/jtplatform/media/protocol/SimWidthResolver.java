package io.github.jtplatform.media.protocol;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 连接级的 SIM 宽度缓存。
 *
 * <p><b>只在第一个可解包上判定一次，之后整条连接复用。</b>三个理由：
 *
 * <ul>
 *   <li><b>省</b>：探测要试算两种解法、各读若干字节，而一条 2Mbps 的视频流每秒上百个包。</li>
 *   <li><b>稳</b>：更要紧的是一致性。逐包判定时，只要有一个包因为分包边界、丢包或畸形数据
 *       导致投票倒向另一边，同一条流的前后帧就会按不同偏移解析——**那比一直解错更难查**：
 *       画面时好时坏，日志里也看不出规律。</li>
 *   <li><b>符合现实</b>：设备的实现是固定的，不会中途换格式。</li>
 * </ul>
 *
 * <p>每条连接一个实例，由 {@code Jt1078RtpDecoder} 持有——Netty 的解码器本来就是每连接一个实例，
 * 不必再往 channel attribute 里塞，少一层间接。
 */
public final class SimWidthResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimWidthResolver.class);

    /**
     * 能同时评判两种解法所需的最少字节数。
     *
     * <p>标准解法的帧类型在偏移 15，扩展解法在偏移 19——读到后者才能比较，故为 20。
     */
    private static final int MIN_BYTES_TO_COMPARE = 20;

    private SimWidth resolved;

    /**
     * 取本连接的 SIM 宽度，必要时判定并缓存。
     *
     * @return 已确定的宽度；若数据还不足以判定则返回 null，调用方应等待更多字节
     */
    public SimWidth resolve(ByteBuf input, int start) {
        if (resolved != null) {
            return resolved;
        }
        // 要评判扩展解法，至少得读到偏移 19 的帧类型字节。
        if (input.readableBytes() < MIN_BYTES_TO_COMPARE) {
            // 字节不够比较两种解法。但若按标准解法这已经是一个**完整**包，那它必然就是标准包：
            // 同一个包的扩展形态要长 4 字节，不可能在更少的字节里读完。
            // 这一分支覆盖的是短透传包——它整包才 18 字节，等不到 20 字节就会永远卡住。
            if (!completeAsStandard(input, start)) {
                return null;
            }
            resolved = SimWidth.STANDARD;
            SimWidthStats.record(resolved, false);
            LOGGER.debug("JT/T 1078 短包按标准 12 位 SIM 解析");
            return resolved;
        }
        SimWidthDetector.Outcome outcome = SimWidthDetector.detect(input, start);
        resolved = outcome.width();
        SimWidthStats.record(resolved, outcome.tied());
        if (outcome.tied()) {
            LOGGER.warn("JT/T 1078 SIM 宽度无法判定（{}），按标准 12 位继续。"
                    + "若画面异常，请核对该设备是否使用 20 位手机号", outcome.reason());
        } else if (resolved.nonStandard()) {
            LOGGER.info("JT/T 1078 流采用 20 位 SIM（BCD[10]，非 1078-2016 标准），判定依据：{}",
                    outcome.reason());
        } else {
            LOGGER.debug("JT/T 1078 流采用标准 12 位 SIM，判定依据：{}", outcome.reason());
        }
        return resolved;
    }

    /** 已判定的宽度，未判定时为 null。供指标统计使用。 */
    public SimWidth resolved() {
        return resolved;
    }

    /** 按标准解法，当前缓冲区里是否已经是一个完整的包。 */
    private static boolean completeAsStandard(ByteBuf input, int start) {
        int available = input.readerIndex() + input.readableBytes();
        if (start + Jt1078Constants.COMMON_HEADER_LENGTH > available) {
            return false;
        }
        int typeAndFragment = input.getUnsignedByte(start + 15);
        int headerLength = switch ((typeAndFragment >>> 4) & 0x0f) {
            case Jt1078Constants.VIDEO_I_FRAME,
                 Jt1078Constants.VIDEO_P_FRAME,
                 Jt1078Constants.VIDEO_B_FRAME -> Jt1078Constants.VIDEO_HEADER_LENGTH;
            case Jt1078Constants.AUDIO_FRAME -> Jt1078Constants.AUDIO_HEADER_LENGTH;
            case Jt1078Constants.TRANSPARENT_DATA -> Jt1078Constants.TRANSPARENT_HEADER_LENGTH;
            default -> -1;
        };
        if (headerLength < 0 || start + headerLength > available) {
            return false;
        }
        int bodyLength = input.getUnsignedShort(start + headerLength - Short.BYTES);
        return start + headerLength + bodyLength <= available;
    }
}
