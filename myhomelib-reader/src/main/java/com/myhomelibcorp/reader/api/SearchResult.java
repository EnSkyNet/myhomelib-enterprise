package com.myhomelibcorp.reader.api;

import java.util.List;

public record SearchResult(
        List<SearchMatch> matches,
        long totalHits,
        long timeMs
) {
    public static SearchResult empty() {
        return new SearchResult(List.of(), 0, 0);
    }

    public boolean isEmpty() {
        return matches.isEmpty();
    }

    public int size() {
        return matches.size();
    }
}