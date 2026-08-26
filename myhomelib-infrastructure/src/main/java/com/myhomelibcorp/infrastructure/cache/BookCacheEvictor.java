package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
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


    @Async
    @EventListener
    public void onBookDeleted(BookDeletedEvent event) {
        if (event.getBookId() != null) {
            log.debug("Очищення кешу для видаленої книги: {}", event.getBookId());
            bookCache.evict(event.getBookId());
        } else {
            log.warn("BookDeletedEvent отримано без BookId");
        }
    }
}