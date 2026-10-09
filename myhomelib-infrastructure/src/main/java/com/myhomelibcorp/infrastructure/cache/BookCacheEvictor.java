package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookCacheEvictor {

    private final BookCache bookCache;

    /**
     * A delete event is published from the same SQLite transaction as the row deletion. Evict only
     * after commit: an asynchronous BEFORE_COMMIT listener can evict, let a concurrent reader cache
     * the still-committed old row, and then leave that stale aggregate resident after the delete.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookDeleted(BookDeletedEvent event) {
        if (event.getBookId() != null) {
            log.debug("Очищення кешу для видаленої книги після commit: {}", event.getBookId());
            bookCache.evictAllScopes(event.getBookId());
        } else {
            log.warn("BookDeletedEvent отримано без BookId");
        }
    }
}
