package com.myhomelibcorp.reader.api;

import java.util.ArrayList;
import java.util.List;

public record TocEntry(
        String title,
        long textOffset,
        int level,
        List<TocEntry> children
) {
    public TocEntry {
        children = children == null ? new ArrayList<>() : new ArrayList<>(children);
    }

    public TocEntry(String title, long textOffset, int level) {
        this(title, textOffset, level, List.of());
    }

    public boolean containsOffset(long offset) {
        return offset >= textOffset && offset - textOffset < 100;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public TocEntry addChild(TocEntry child) {
        if (child != null) children.add(child);
        return this;
    }
}
