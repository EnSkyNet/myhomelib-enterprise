package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.port.out.cache.AuthorCache;
import com.myhomelibcorp.application.port.out.cache.GenreCache;
import com.myhomelibcorp.application.port.out.cache.SeriesCache;
import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class CacheEvictor {

    private final AuthorCache authorCache;
    private final GenreCache genreCache;
    private final SeriesCache seriesCache;

    /** Clear catalog projections only for a deletion that actually committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookDeleted(BookDeletedEvent event) {
        log.debug("Очищення кешів після commit видалення книги: {}", event.getBookId());
        authorCache.clear();
        genreCache.clear();
        seriesCache.clear();
        log.debug("Кеші очищено");
    }
}
