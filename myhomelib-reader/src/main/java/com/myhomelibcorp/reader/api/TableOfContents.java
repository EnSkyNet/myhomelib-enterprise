package com.myhomelibcorp.reader.api;

import java.util.List;

public interface TableOfContents {

    List<TocEntry> entries();

    default TocEntry findEntryAt(long textOffset) {
        for (TocEntry entry : entries()) {
            if (entry.containsOffset(textOffset)) {
                return entry;
            }
        }
        return null;
    }

    default int size() {
        return entries().size();
    }

    default boolean isEmpty() {
        return entries().isEmpty();
    }
}