package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.port.out.cache.AuthorCache;
import com.myhomelibcorp.application.port.out.cache.GenreCache;
import com.myhomelibcorp.application.port.out.cache.SeriesCache;
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
public class CacheEvictor {

    private final AuthorCache authorCache;
    private final GenreCache genreCache;
    private final SeriesCache seriesCache;

    @Async
    @EventListener
    public void onBookUpdated(BookUpdatedEvent event) {
        log.debug("Очищення кешів при оновленні книги: {}", event.getBookId());
        // Очищаємо всі кеші, бо оновлення книги може вплинути на авторів, жанри, серії
        authorCache.clear();
        genreCache.clear();
        seriesCache.clear();
        log.debug("Кеші очищено");
    }

    @Async
    @EventListener
    public void onBookDeleted(BookDeletedEvent event) {
        log.debug("Очищення кешів при видаленні книги: {}", event.getBookId());
        authorCache.clear();
        genreCache.clear();
        seriesCache.clear();
        log.debug("Кеші очищено");
    }
}