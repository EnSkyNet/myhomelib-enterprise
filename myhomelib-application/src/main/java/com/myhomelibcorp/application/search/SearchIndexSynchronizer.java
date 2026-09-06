package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps the derived Lucene document state consistent with committed catalog rows.
 * Database writes remain authoritative: when called inside a Spring transaction the
 * index update is deferred until AFTER_COMMIT, so a rolled-back DB mutation can never
 * leak into the search index.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SearchIndexSynchronizer {

    private static final int QUERY_BATCH_SIZE = 400;

    private final BookQueryRepository bookQueryRepository;
    private final SearchIndexer searchIndexer;
    private final SearchIndexLifecycle searchIndexLifecycle;

    public void synchronizeAfterCommit(List<BookId> bookIds) {
        List<BookId> ids = normalize(bookIds);
        if (ids.isEmpty()) return;

        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            // Persist stale intent BEFORE the DB commit. If the process dies after SQLite commits but before
            // afterCommit executes, restart validation will still reject/rebuild the derived Lucene index.
            searchIndexLifecycle.markCurrentIndexDirty();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    synchronizeDerivedIndexSafely(ids);
                }
            });
            return;
        }

        synchronizeSafelyNow(ids);
    }

    /**
     * Immediate best-effort synchronization for a DB mutation that is already committed.
     * A selective failure rolls Lucene back and falls back to a full rebuild.
     *
     * @return true when either the selective update or the fallback rebuild succeeded.
     */
    public boolean synchronizeSafelyNow(List<BookId> bookIds) {
        List<BookId> ids = normalize(bookIds);
        if (ids.isEmpty()) return true;
        searchIndexLifecycle.markCurrentIndexDirty();
        try {
            applySelective(ids);
            searchIndexLifecycle.markCurrentIndexSynchronized();
            return true;
        } catch (RuntimeException selectiveFailure) {
            log.error("Selective Lucene synchronization failed for {} committed books; rebuilding derived index",
                    ids.size(), selectiveFailure);
            try {
                searchIndexer.rebuildIndex();
                searchIndexLifecycle.markCurrentIndexSynchronized();
                return true;
            } catch (RuntimeException rebuildFailure) {
                selectiveFailure.addSuppressed(rebuildFailure);
                log.error("Full Lucene rebuild also failed after committed catalog mutation", rebuildFailure);
                return false;
            }
        }
    }

    /** Full rebuild with freshness marker semantics. A failed rebuild intentionally leaves the index dirty. */
    public boolean rebuildSafelyNow() {
        searchIndexLifecycle.markCurrentIndexDirty();
        try {
            searchIndexer.rebuildIndex();
            searchIndexLifecycle.markCurrentIndexSynchronized();
            return true;
        } catch (RuntimeException failure) {
            log.error("Full Lucene rebuild failed; index remains marked stale for restart recovery", failure);
            return false;
        }
    }

    /** Immediate strict synchronization for orchestration that wants failures propagated. */
    void synchronizeNow(List<BookId> bookIds) {
        List<BookId> ids = normalize(bookIds);
        if (ids.isEmpty()) return;
        searchIndexLifecycle.markCurrentIndexDirty();
        applySelective(ids);
        searchIndexLifecycle.markCurrentIndexSynchronized();
    }

    private void synchronizeDerivedIndexSafely(List<BookId> ids) {
        synchronizeSafelyNow(ids);
    }

    private void applySelective(List<BookId> ids) {
        boolean begun = false;
        try {
            searchIndexer.beginAtomicUpdate();
            begun = true;

            for (int from = 0; from < ids.size(); from += QUERY_BATCH_SIZE) {
                List<BookId> batch = ids.subList(from, Math.min(ids.size(), from + QUERY_BATCH_SIZE));
                Map<BookId, Book> current = bookQueryRepository.findByIds(batch).stream()
                        .filter(book -> book != null && book.getId() != null)
                        .collect(Collectors.toMap(Book::getId, Function.identity(), (left, right) -> left));

                for (BookId id : batch) {
                    Book book = current.get(id);
                    if (book == null || book.isDeleted()) searchIndexer.deleteBook(id);
                    else searchIndexer.indexBook(book);
                }
            }
            searchIndexer.commit();
        } catch (RuntimeException error) {
            if (begun) {
                try {
                    searchIndexer.rollbackAtomicUpdate();
                } catch (RuntimeException rollbackFailure) {
                    error.addSuppressed(rollbackFailure);
                }
            }
            throw error;
        }
    }

    private static List<BookId> normalize(List<BookId> source) {
        if (source == null || source.isEmpty()) return List.of();
        LinkedHashSet<BookId> unique = new LinkedHashSet<>();
        for (BookId id : source) if (id != null) unique.add(id);
        return unique.isEmpty() ? List.of() : new ArrayList<>(unique);
    }
}
