package io.github.jtconsole.domain;

import java.util.List;

/**
 * 一页通知。
 *
 * @param filtered 是否因数据范围隐藏了本页里的部分通知。前端据此说明「已按你的数据范围过滤」，
 *                 免得用户以为平台漏报——与首页要点同一处理
 */
public record NoticePage(
        List<NoticeView> items, long total, int page, int pageSize, boolean filtered) {

    public NoticePage {
        items = List.copyOf(items);
    }
}
