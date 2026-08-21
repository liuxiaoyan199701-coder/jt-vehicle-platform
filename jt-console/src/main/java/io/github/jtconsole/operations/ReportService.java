package io.github.jtconsole.operations;

import io.github.jtconsole.domain.VehicleReportRow;
import io.github.jtconsole.repository.DailyStatRepository;
import io.github.jtconsole.security.DataScope;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReportService {

    private static final int MAX_RANGE_DAYS = 92;
    private final DailyStatRepository stats;

    public ReportService(DailyStatRepository stats) {
        this.stats = stats;
    }

    public List<VehicleReportRow> vehicleReport(String start, String end, DataScope scope) {
        LocalDate startDate = parseDate(start, "开始日期不合法");
        LocalDate endDate = parseDate(end, "结束日期不合法");
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        if (endDate.toEpochDay() - startDate.toEpochDay() + 1 > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("日期跨度不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        return stats.aggregateByVehicle(startDate.toString(), endDate.toString(), scope);
    }

    private static LocalDate parseDate(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(message, failure);
        }
    }
}
