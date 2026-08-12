package io.github.jtconsole.ingest;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 最近投递事件的内存环形缓冲，用于排查「设备连上了但界面上看不到」这类问题。
 *
 * <p>只保留摘要（消息 ID、类型、是否带有效坐标、被跳过的原因），不保留完整 payload，
 * 避免把多媒体、透传这类大消息留在内存里。仅供诊断，重启即清空。
 */
@Component
public class RecentEventLog {

    private static final int CAPACITY = 100;

    private final Deque<Entry> entries = new ArrayDeque<>(CAPACITY);

    public synchronized void record(MessageEnvelope envelope, String result, String outcome) {
        if (entries.size() >= CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(new Entry(
                Instant.now().toString(),
                envelope == null ? null : trimToNull(envelope.eventId()),
                envelope == null ? null : trimToNull(envelope.deviceId()),
                envelope == null ? null : envelope.messageId(),
                envelope == null ? null : envelope.type(),
                result,
                outcome));
    }

    /** 最近的排在前面 */
    public synchronized List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        Entry[] snapshot = entries.toArray(new Entry[0]);
        for (int i = snapshot.length - 1; i >= 0 && result.size() < limit; i--) {
            Entry entry = snapshot[i];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", entry.at());
            row.put("eventId", entry.eventId());
            row.put("deviceId", entry.deviceId());
            row.put("messageId", entry.messageId());
            row.put("messageIdHex", entry.messageId() == null
                    ? null
                    : String.format("0x%04X", entry.messageId()));
            row.put("type", entry.type());
            row.put("result", entry.result());
            row.put("outcome", entry.outcome());
            result.add(row);
        }
        return result;
    }

    /** 按消息 ID 统计各类报文的数量，用来一眼看出设备到底在发什么 */
    public synchronized List<Map<String, Object>> summary() {
        Map<String, int[]> counters = new LinkedHashMap<>();
        for (Entry entry : entries) {
            String key = entry.messageId() == null
                    ? "unknown"
                    : String.format("0x%04X", entry.messageId());
            counters.computeIfAbsent(key, ignored -> new int[1])[0]++;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        counters.forEach((messageId, count) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("messageId", messageId);
            row.put("name", describe(messageId));
            row.put("count", count[0]);
            result.add(row);
        });
        return result;
    }

    private static String describe(String messageIdHex) {
        return switch (messageIdHex) {
            case "0x0002" -> "终端心跳";
            case "0x0100" -> "终端注册";
            case "0x0102" -> "终端鉴权";
            case "0x0200" -> "位置信息汇报";
            case "0x0201" -> "位置查询应答";
            case "0x0704" -> "定位数据批量上传";
            case "0x0001" -> "终端通用应答";
            case "0x0003" -> "终端注销";
            case "0x0800" -> "多媒体事件信息上传";
            case "0x0801" -> "多媒体数据上传";
            case "0x1003" -> "终端上传音视频属性";
            case "0x1205" -> "终端上传音视频资源列表";
            case "0x9105" -> "实时音视频传输状态通知";
            default -> "其他";
        };
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Entry(
            String at,
            String eventId,
            String deviceId,
            Long messageId,
            String type,
            String result,
            String outcome) {}
}
