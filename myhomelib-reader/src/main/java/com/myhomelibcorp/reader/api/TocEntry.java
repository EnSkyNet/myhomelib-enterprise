package com.myhomelibcorp.reader.api;

import java.util.ArrayList;
import java.util.List;

public record TocEntry(
        String title,
        long textOffset,
        int level,
        List<TocEntry> children
) {
    public TocEntry(String title, long textOffset, int level) {
        this(title, textOffset, level, new ArrayList<>());
    }

    public boolean containsOffset(long offset) {
        return textOffset <= offset && offset < textOffset + 100;
    }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }

    public TocEntry addChild(TocEntry child) {
        if (children == null) {
            return new TocEntry(title, textOffset, level, new ArrayList<>());
        }
        children.add(child);
        return this;
    }
}