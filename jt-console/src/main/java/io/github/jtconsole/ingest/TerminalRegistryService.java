package io.github.jtconsole.ingest;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Terminal;
import io.github.jtconsole.repository.TerminalRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把注册与鉴权信封落成终端台账。
 *
 * <p>数据早就在门口了：网关把 0x0100 归为 {@code register}、0x0102 归为
 * {@code authentication} 一直在投递，payload 里终端自报的制造商、型号、车牌一应俱全，
 * 控制台此前只是没有消费。所以这个能力在网关侧零改动。
 *
 * <p>台账不是关键投影：写失败只 warn，位置与在线时间照常处理。
 */
@Service
public class TerminalRegistryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TerminalRegistryService.class);
    private static final Set<String> HANDLED_TYPES = Set.of("register", "authentication");

    private final TerminalRepository terminals;

    public TerminalRegistryService(TerminalRepository terminals) {
        this.terminals = terminals;
    }

    /** @return 是否是台账关心的信封；返回 true 也不代表写成功（失败只 warn） */
    public boolean handle(MessageEnvelope envelope) {
        if (envelope == null || envelope.type() == null
                || !HANDLED_TYPES.contains(envelope.type().toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        try {
            terminals.upsert(toTerminal(envelope));
        } catch (RuntimeException failure) {
            // 台账缺一行远不如把位置投影拖垮严重。
            LOGGER.warn("终端台账写入失败：device={}", envelope.deviceId(), failure);
        }
        return true;
    }

    /**
     * <b>两个 deviceId 同名不同义，这是全类唯一的高危点。</b>
     *
     * <ul>
     *   <li>{@code envelope.deviceId()} 是终端手机号（{@code 138000000000}），全平台主键，
     *       台账的 {@code device_id} 必须取它——取错了台账与 {@code vehicle} 永远 join 不上，
     *       每台设备都显示「未建档」。</li>
     *   <li>{@code payload.get("deviceId")} 是 0x0100 正文里终端自报的终端 ID
     *       （{@code 1380000}），只能落到 {@code terminal_id} 这个附加列。</li>
     * </ul>
     *
     * <p>写反了不会抛任何异常，接口照样 200——只有页面上「全都是未建档」这一个症状。
     * {@code TerminalRegistryContractTest} 钉住这处映射。
     */
    private static Terminal toTerminal(MessageEnvelope envelope) {
        Map<String, Object> payload = envelope.payload() == null ? Map.of() : envelope.payload();
        String seenAt = normalizeTime(envelope.receivedAt());
        return new Terminal(
                envelope.deviceId().trim(),
                text(payload.get("deviceId")),
                text(payload.get("makerId")),
                text(payload.get("deviceModel")),
                integer(payload.get("provinceId")),
                integer(payload.get("cityId")),
                text(payload.get("plateNo")),
                integer(payload.get("plateColor")),
                text(envelope.protocolVersion()),
                seenAt,
                seenAt,
                describe(envelope),
                null);
    }

    /** 「最近一次发生了什么」，用来一眼认出反复连不上的终端。 */
    private static String describe(MessageEnvelope envelope) {
        return "register".equalsIgnoreCase(envelope.type()) ? "注册" : "鉴权";
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String normalizeTime(String receivedAt) {
        if (receivedAt == null || receivedAt.isBlank()) {
            return Timestamps.now();
        }
        try {
            return Timestamps.of(Instant.parse(receivedAt.trim()));
        } catch (DateTimeParseException notAnInstant) {
            return Timestamps.normalize(receivedAt);
        }
    }
}
