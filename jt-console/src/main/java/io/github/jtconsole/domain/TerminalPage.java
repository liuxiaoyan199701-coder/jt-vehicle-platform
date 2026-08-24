package io.github.jtconsole.domain;

import java.util.List;

public record TerminalPage(List<TerminalSummary> items, long total, int page, int pageSize) {
    public TerminalPage {
        items = List.copyOf(items);
    }
}
