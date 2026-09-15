package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;

/** Best-effort refresh of derived Lucene documents after user-data changes affect searchable filters. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookSearchIndexRefreshService {
    private final BookQueryRepository books;
    private final SearchIndexer indexer;

    public void refreshBook(String bookId) {
        if (bookId == null || bookId.isBlank()) return;
        refreshBooks(java.util.List.of(bookId));
    }

    public void refreshBooks(Collection<String> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String value : bookIds) if (value != null && !value.isBlank()) ids.add(value.trim());
        if (ids.isEmpty()) return;
        try {
            java.util.List<BookId> resolvedIds = ids.stream().map(BookId::fromString).toList();
            var existing = books.findByIds(resolvedIds);
            if (!existing.isEmpty()) {
                indexer.indexAll(existing);
                indexer.commit();
            }
        } catch (RuntimeException error) {
            // The search index is derived state. Never roll back or fail a successful annotation mutation
            // merely because the index is temporarily unavailable; normal index-health tooling can rebuild it.
            log.warn("Could not refresh Lucene activity flags for {} book(s): {}", ids.size(), error.toString());
        }
    }
}
