// myhomelib-reader/src/main/java/com/myhomelibcorp/reader/service/ReaderSearchService.java
package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.TextStorage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ReaderSearchService {

    public record SearchResult(int matchIndex, long textOffset, int paragraphIndex, String context) {}

    public List<SearchResult> search(ReaderDocument document, String query) {
        if (document == null || query == null || query.isBlank()) {
            return List.of();
        }

        TextStorage text = document.text();
        if (text == null || text.length() == 0) {
            return List.of();
        }

        String fullText = text.getFullText().toLowerCase();
        String lowerQuery = query.toLowerCase();

        List<SearchResult> results = new ArrayList<>();
        int index = 0;
        int matchCount = 0;

        while (index < fullText.length()) {
            int pos = fullText.indexOf(lowerQuery, index);
            if (pos == -1) {
                break;
            }

            // Знаходимо параграф для цієї позиції
            var paragraph = text.findParagraphAt(pos);
            if (paragraph != null) {
                // Контекст (50 символів до і після)
                int start = Math.max(0, pos - 50);
                int end = Math.min(fullText.length(), pos + query.length() + 50);
                String context = fullText.substring(start, end);

                results.add(new SearchResult(
                        matchCount++,
                        pos,
                        paragraph.index(),
                        context
                ));
            }

            index = pos + 1;
        }

        log.debug("🔍 Знайдено {} збігів для '{}'", results.size(), query);
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