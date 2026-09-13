package com.myhomelibcorp.application.content.index;

import java.util.List;

public record ContentIndexPage(List<ContentIndexHit> hits, long total, int offset, int limit) {
    public ContentIndexPage {
        hits = hits == null ? List.of() : List.copyOf(hits);
        total = Math.max(0L, total);
        offset = Math.max(0, offset);
        limit = Math.max(1, limit);
    }
}
