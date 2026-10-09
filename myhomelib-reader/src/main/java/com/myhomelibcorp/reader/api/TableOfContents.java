package com.myhomelibcorp.reader.api;

import java.util.List;

public interface TableOfContents {

    List<TocEntry> entries();

    default TocEntry findEntryAt(long textOffset) {
        List<TocEntry> entries = entries();
        if (entries == null || entries.isEmpty()) return null;
        for (TocEntry entry : entries) {
            if (entry != null && entry.containsOffset(textOffset)) {
                return entry;
            }
        }
        return null;
    }

    default int size() {
        List<TocEntry> entries = entries();
        return entries == null ? 0 : entries.size();
    }

    default boolean isEmpty() {
        List<TocEntry> entries = entries();
        return entries == null || entries.isEmpty();
    }
}
