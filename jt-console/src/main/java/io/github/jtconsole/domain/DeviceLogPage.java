package io.github.jtconsole.domain;

import java.util.List;

public record DeviceLogPage(List<DeviceLog> items, long total, int page, int pageSize) {
    public DeviceLogPage {
        items = List.copyOf(items);
    }
}
