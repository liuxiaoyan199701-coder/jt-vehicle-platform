package io.github.jtconsole.domain;

import java.util.List;

public record AlarmPage(List<AlarmEvent> items, long total, int page, int pageSize) {
    public AlarmPage {
        items = List.copyOf(items);
    }
}
