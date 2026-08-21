package io.github.jtconsole.repository;

import io.github.jtconsole.domain.Waybill;
import io.github.jtconsole.security.DataScope;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WaybillRepository {
    private static final int PREVIEW_MAX_CHARS = 2_000;

    private final JdbcClient jdbc;

    public WaybillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** event_id 唯一键与 processed_event 事务共同保证重投递不重复。 */
    public boolean insertIgnore(
            String eventId,
            Long tenantId,
            String deviceId,
            String reportedAt,
            String receivedAt,
            String rawBase64,
            int rawLength,
            String createdAt) {
        return jdbc.sql("""
                        INSERT OR IGNORE INTO waybill
                            (event_id, tenant_id, device_id, reported_at, received_at,
                             raw_base64, raw_length, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(eventId).param(tenantId).param(deviceId)
                .param(reportedAt).param(receivedAt).param(rawBase64)
                .param(rawLength).param(createdAt)
                .update() == 1;
    }

    public WaybillPage findByDevice(
            String deviceId, int page, int pageSize, DataScope scope) {
        if (scope.empty()) {
            return new WaybillPage(List.of(), 0, page, pageSize);
        }
        List<Object> base = new ArrayList<>();
        base.add(deviceId);
        base.addAll(scope.parameters());
        String where = " WHERE device_id = ?" + scope.deviceCondition("device_id");
        Integer total = jdbc.sql("SELECT COUNT(*) FROM waybill" + where)
                .params(base).query(Integer.class).single();
        if (total == null || total == 0) {
            return new WaybillPage(List.of(), 0, page, pageSize);
        }
        List<Object> params = new ArrayList<>(base);
        params.add(pageSize);
        params.add((page - 1) * pageSize);
        List<StoredWaybill> stored = jdbc.sql("""
                        SELECT id, device_id AS deviceId, reported_at AS reportedAt,
                               received_at AS receivedAt, raw_base64 AS rawBase64,
                               raw_length AS rawLength
                        FROM waybill
                        """ + where + " ORDER BY reported_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(params)
                .query(StoredWaybill.class)
                .list();
        return new WaybillPage(stored.stream().map(WaybillRepository::toView).toList(),
                total, page, pageSize);
    }

    public Optional<RawWaybill> findRaw(long id, String deviceId, DataScope scope) {
        if (scope.empty()) {
            return Optional.empty();
        }
        List<Object> params = new ArrayList<>();
        params.add(id);
        params.add(deviceId);
        params.addAll(scope.parameters());
        return jdbc.sql("""
                        SELECT id, device_id AS deviceId, reported_at AS reportedAt,
                               raw_base64 AS rawBase64, raw_length AS rawLength
                        FROM waybill
                        WHERE id = ? AND device_id = ?
                        """ + scope.deviceCondition("device_id"))
                .params(params)
                .query(RawWaybill.class)
                .optional();
    }

    /** 与审计清理同样按小批次删除，返回本批数量。 */
    public int deleteOlderThan(String cutoff, int batchSize) {
        return jdbc.sql("""
                        DELETE FROM waybill WHERE id IN (
                            SELECT id FROM waybill WHERE reported_at < ?
                            ORDER BY reported_at, id LIMIT ?)
                        """)
                .param(cutoff).param(batchSize)
                .update();
    }

    private static Waybill toView(StoredWaybill stored) {
        byte[] raw = decode(stored.rawBase64());
        Utf8Preview preview = preview(raw);
        return new Waybill(stored.id(), stored.deviceId(), stored.reportedAt(), stored.receivedAt(),
                stored.rawLength(), preview.text(), preview.valid());
    }

    /** 严格 UTF-8 解码；非法字节不抛到接口层，而是给出明确降级文案。 */
    static Utf8Preview preview(byte[] raw) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw));
            String text = decoded.toString();
            if (text.length() > PREVIEW_MAX_CHARS) {
                text = text.substring(0, PREVIEW_MAX_CHARS) + "…";
            }
            return new Utf8Preview(text, true);
        } catch (CharacterCodingException malformed) {
            return new Utf8Preview("该运单不是有效 UTF-8 文本，请下载原文查看。", false);
        }
    }

    public static byte[] decode(String rawBase64) {
        return Base64.getDecoder().decode(rawBase64 == null ? "" : rawBase64);
    }

    public record WaybillPage(List<Waybill> items, int total, int page, int pageSize) {}

    public record RawWaybill(
            long id, String deviceId, String reportedAt, String rawBase64, int rawLength) {
        public byte[] bytes() {
            return decode(rawBase64);
        }
    }

    private record StoredWaybill(
            long id, String deviceId, String reportedAt, String receivedAt,
            String rawBase64, int rawLength) {}

    record Utf8Preview(String text, boolean valid) {}
}
