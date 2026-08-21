package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.VehicleReportRow;
import io.github.jtconsole.operations.ReportService;
import io.github.jtconsole.security.DataScope;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String[] COLUMNS = {"设备号", "车牌号", "总里程(km)", "活跃天数", "告警总数", "最高速(km/h)"};

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/vehicles")
    @RequirePermission(Permissions.DASHBOARD_VIEW)
    public ApiResponse<List<VehicleReportRow>> vehicles(
            @RequestParam String start, @RequestParam String end, DataScope scope) {
        return ApiResponse.ok(reports.vehicleReport(start, end, scope));
    }

    @GetMapping(value = "/vehicles/export", produces = "text/csv")
    @RequirePermission(Permissions.DASHBOARD_VIEW)
    public ResponseEntity<byte[]> export(
            @RequestParam String start, @RequestParam String end, DataScope scope) {
        List<VehicleReportRow> rows = reports.vehicleReport(start, end, scope);
        byte[] csv = toCsv(rows);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=vehicle-report-" + start + "-" + end + ".csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    private static byte[] toCsv(List<VehicleReportRow> rows) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.join(",", COLUMNS)).append("\r\n");
        for (VehicleReportRow row : rows) {
            builder.append(csv(row.deviceId())).append(',')
                    .append(csv(row.plateNo())).append(',')
                    .append(row.totalDistanceKm()).append(',')
                    .append(row.activeDays()).append(',')
                    .append(row.totalAlarms()).append(',')
                    .append(row.maxSpeedKph()).append("\r\n");
        }
        byte[] body = builder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, result, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, result, UTF8_BOM.length, body.length);
        return result;
    }

    private static String csv(String value) {
        if (value == null) return "";
        String v = value;
        boolean needsQuotes = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        if (needsQuotes) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
