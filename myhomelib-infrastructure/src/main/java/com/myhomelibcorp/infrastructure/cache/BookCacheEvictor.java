package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
import com.myhomelibcorp.domain.event.book.BookUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookCacheEvictor {

    private final BookCache bookCache;

    @Async  // <-- використовує пул "taskExecutor" за замовчуванням
    @EventListener
    public void onBookUpdated(BookUpdatedEvent event) {
        log.debug("Очищення кешу для оновленої книги: {}", event.getBookId());
        bookCache.evict(event.getBookId());
    }

    @Async
    @EventListener
    public void onBookDeleted(BookDeletedEvent event) {
        log.debug("Очищення кешу для видаленої книги: {}", event.getBookId());
        bookCache.evict(event.getBookId());
    }
}