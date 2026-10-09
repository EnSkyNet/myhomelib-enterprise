package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.api.ParagraphInfo;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.TextStorage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.CancellationException;

/** Пошук по абзацах без створення lowercase-копії всієї книги. */
@Slf4j
public class ReaderSearchService {

    private static final int MAX_RESULTS = 10_000;
    private static final int SEARCH_WINDOW_CHARS = 256 * 1024;
    private static final int MAX_QUERY_CHARS = 4_096;

    public record SearchResult(int matchIndex, long textOffset, int paragraphIndex, String context) {}

    public List<SearchResult> search(ReaderDocument document, String query) {
        if (document == null || query == null || query.isBlank()) return List.of();
        if (query.length() > MAX_QUERY_CHARS)
            throw new IllegalArgumentException("Пошуковий запит надто довгий (максимум " + MAX_QUERY_CHARS + " символів)");

        TextStorage storage = document.text();
        if (storage == null || storage.length() == 0) return List.of();

        Pattern needle = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<ParagraphInfo> paragraphs = storage.getParagraphs();
        List<SearchResult> results = new ArrayList<>();
        int matchIndex = 0;

        for (int p = 0; p < paragraphs.size() && results.size() < MAX_RESULTS; p++) {
            checkCancelled();
            ParagraphInfo paragraph = paragraphs.get(p);
            int paragraphStart = paragraph.offset();
            int paragraphEnd = p + 1 < paragraphs.size() ? paragraphs.get(p + 1).offset() : storage.length();
            if (paragraphStart >= paragraphEnd) continue;

            // Search a bounded window instead of lower-casing/copying one enormous
            // paragraph. Extend by needle length - 1 so matches crossing a window
            // boundary are still found exactly once.
            int windowStart = paragraphStart;
            while (windowStart < paragraphEnd && results.size() < MAX_RESULTS) {
                checkCancelled();
                int primaryEnd = boundedEnd(windowStart, SEARCH_WINDOW_CHARS, paragraphEnd);
                int readEnd = boundedEnd(primaryEnd, Math.max(0, query.length() - 1), paragraphEnd);
                String haystack = storage.getText(windowStart, readEnd);
                var matcher = needle.matcher(haystack);
                while (matcher.find() && results.size() < MAX_RESULTS) {
                    int absolute = windowStart + matcher.start();
                    if (absolute >= primaryEnd) break; // next window owns this match

                    int contextStart = Math.max(paragraphStart, absolute - 50);
                    int contextEnd = boundedEnd(windowStart, matcher.end() + 50, paragraphEnd);
                    results.add(new SearchResult(
                            matchIndex++,
                            absolute,
                            paragraph.index(),
                            storage.getText(contextStart, contextEnd).trim()
                    ));
                }
                windowStart = primaryEnd;
            }
        }

        log.debug("🔍 '{}' -> {} matches", query, results.size());
        return results;
    }

    private static int boundedEnd(int start, int increment, int limit) {
        return (int) Math.min((long) limit, (long) start + increment);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Reader search cancelled");
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
