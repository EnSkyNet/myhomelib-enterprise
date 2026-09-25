package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;

/** Best-effort refresh of derived Lucene documents after user-data changes affect searchable filters. */
@Service
@Slf4j
public class BookSearchIndexRefreshService {
    private final SearchIndexSynchronizer synchronizer;

    // Compatibility-only dependencies retained for isolated tests that construct this service directly.
    private final BookQueryRepository legacyBooks;
    private final SearchIndexer legacyIndexer;

    @Autowired
    public BookSearchIndexRefreshService(SearchIndexSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
        this.legacyBooks = null;
        this.legacyIndexer = null;
    }

    /** Compatibility constructor for focused non-Spring tests. Production wiring uses SearchIndexSynchronizer. */
    public BookSearchIndexRefreshService(BookQueryRepository books, SearchIndexer indexer) {
        this.synchronizer = null;
        this.legacyBooks = books;
        this.legacyIndexer = indexer;
    }

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
            if (synchronizer != null) {
                if (!synchronizer.synchronizeSafelyNow(resolvedIds)) {
                    log.warn("Could not refresh Lucene activity flags for {} book(s); index remains marked stale", ids.size());
                }
                return;
            }

            // Focused-test compatibility path. Spring production never uses this branch.
            if (legacyBooks == null || legacyIndexer == null) return;
            var existing = legacyBooks.findByIds(resolvedIds);
            if (!existing.isEmpty()) {
                legacyIndexer.indexAll(existing);
                legacyIndexer.commit();
            }
        } catch (RuntimeException error) {
            // The search index is derived state. Never roll back or fail a successful annotation mutation
            // merely because the index is temporarily unavailable; the synchronizer leaves it DIRTY for recovery.
            log.warn("Could not refresh Lucene activity flags for {} book(s): {}", ids.size(), error.toString());
        }
    }
}
