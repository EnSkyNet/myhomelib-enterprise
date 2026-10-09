package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.content.indexing.ContentIndexAutoSchedulingService;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DomainBookEventHandler {

    private final SearchIndexSynchronizer searchIndexSynchronizer;
    private final ContentIndexAutoSchedulingService contentIndexScheduling;

    /**
     * SQLite is authoritative. Derived-index failures after commit must never escape back to the
     * caller as though the already-committed delete had failed. SearchIndexSynchronizer leaves the
     * Lucene freshness marker dirty and attempts a full rebuild when selective synchronization fails.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBookDeleted(BookDeletedEvent event) {
        if (event == null || event.getBookId() == null) {
            log.warn("BookDeletedEvent отримано без BookId; похідні індекси не оновлено");
            return;
        }

        var bookId = event.getBookId();
        log.debug("Отримано committed BookDeletedEvent для книги: {}", bookId);
        try {
            if (!searchIndexSynchronizer.synchronizeSafelyNow(List.of(bookId))) {
                log.error("Lucene залишився stale після видалення книги {}; відновлення буде повторено lifecycle-механізмом", bookId);
            }
        } catch (RuntimeException failure) {
            // Even mark-dirty can fail because of filesystem/runtime damage. The database delete is
            // already committed, so report and keep the application result truthful.
            log.error("Не вдалося синхронізувати Lucene після committed видалення книги {}", bookId, failure);
        }

        try {
            contentIndexScheduling.scheduleCurrentCollection(new ImportChangeSet(
                    Set.of(), Set.of(), Set.of(bookId.asString()), true));
        } catch (RuntimeException failure) {
            try {
                contentIndexScheduling.markCurrentCollectionStale();
            } catch (RuntimeException staleMarkFailure) {
                failure.addSuppressed(staleMarkFailure);
            }
            log.error("Не вдалося синхронізувати full-text індекс після видалення книги {}", bookId, failure);
        }
    }
}
