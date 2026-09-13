package com.myhomelibcorp.application.content.search;

import java.util.List;

public record ContentSearchResultPage(List<ContentSearchResultItem> items, long total, int offset, int limit) {
    public ContentSearchResultPage {
        items = items == null ? List.of() : List.copyOf(items);
        total = Math.max(0L, total);
        offset = Math.max(0, offset);
        limit = Math.max(1, limit);
    }
    public static ContentSearchResultPage empty(int limit) { return new ContentSearchResultPage(List.of(), 0L, 0, limit); }
}
