package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.api.ParagraphInfo;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.TextStorage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Пошук по абзацах без створення lowercase-копії всієї книги. */
@Slf4j
public class ReaderSearchService {

    private static final int MAX_RESULTS = 10_000;

    public record SearchResult(int matchIndex, long textOffset, int paragraphIndex, String context) {}

    public List<SearchResult> search(ReaderDocument document, String query) {
        if (document == null || query == null || query.isBlank()) {
            return List.of();
        }

        TextStorage storage = document.text();
        if (storage == null || storage.length() == 0) {
            return List.of();
        }

        String needle = query.toLowerCase(Locale.ROOT);
        List<ParagraphInfo> paragraphs = storage.getParagraphs();
        List<SearchResult> results = new ArrayList<>();
        int matchIndex = 0;

        for (int p = 0; p < paragraphs.size() && results.size() < MAX_RESULTS; p++) {
            ParagraphInfo paragraph = paragraphs.get(p);
            int start = paragraph.offset();
            int end = p + 1 < paragraphs.size() ? paragraphs.get(p + 1).offset() : storage.length();
            if (start >= end) continue;

            String original = storage.getText(start, end);
            String haystack = original.toLowerCase(Locale.ROOT);
            int from = 0;
            while (from < haystack.length() && results.size() < MAX_RESULTS) {
                int local = haystack.indexOf(needle, from);
                if (local < 0) break;

                int contextStart = Math.max(0, local - 50);
                int contextEnd = Math.min(original.length(), local + query.length() + 50);
                results.add(new SearchResult(
                        matchIndex++,
                        start + local,
                        paragraph.index(),
                        original.substring(contextStart, contextEnd).trim()
                ));
                from = local + Math.max(1, needle.length());
            }
        }

        log.debug("🔍 '{}' -> {} matches", query, results.size());
        return results;
    }

    public List<ReaderPosition> searchPositions(ReaderDocument document, String query) {
        return search(document, query).stream()
                .map(r -> new ReaderPosition(
                        document.chapterIndexAt(r.textOffset()),
                        r.textOffset(),
                        r.paragraphIndex(),
                        0
                ))
                .toList();
    }
}
