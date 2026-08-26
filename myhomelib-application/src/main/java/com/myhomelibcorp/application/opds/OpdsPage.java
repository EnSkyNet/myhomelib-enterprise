package com.myhomelibcorp.application.opds;

import java.util.List;

public record OpdsPage<T>(List<T> items, long total, int offset, int limit) {
    public OpdsPage {
        items = items == null ? List.of() : List.copyOf(items);
        offset = Math.max(0, offset);
        limit = Math.max(1, limit);
    }
    public boolean hasPrevious() { return offset > 0; }
    public boolean hasNext() { return (long) offset + items.size() < total; }
}
