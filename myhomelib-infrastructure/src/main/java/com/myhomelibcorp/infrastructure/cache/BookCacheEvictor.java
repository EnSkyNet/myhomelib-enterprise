package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
import com.myhomelibcorp.domain.event.book.BookUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookCacheEvictor {

    private final BookCache bookCache;

    @EventListener
    public void onBookUpdated(BookUpdatedEvent event) {
        log.debug("Очищення кешу для оновленої книги: {}", event.getBookId());
        bookCache.evict(event.getBookId());
    }

    @EventListener
    public void onBookDeleted(BookDeletedEvent event) {
        log.debug("Очищення кешу для видаленої книги: {}", event.getBookId());
        bookCache.evict(event.getBookId());
    }
}