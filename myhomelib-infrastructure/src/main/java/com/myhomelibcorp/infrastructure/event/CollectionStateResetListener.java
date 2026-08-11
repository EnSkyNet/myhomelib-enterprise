package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.event.CollectionOpenedEvent;
import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.cache.DictionaryCache;
import com.myhomelibcorp.application.port.out.cache.SearchCache;
import com.myhomelibcorp.application.port.out.cover.CoverCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Слухач, який очищує всі кеші при відкритті нової колекції.
 * Запобігає змішуванню даних між бібліотеками.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionStateResetListener {

    private final BookCache bookCache;
    private final DictionaryCache dictionaryCache;
    private final SearchCache searchCache;
    private final CoverCache coverCache;

    @EventListener
    public void onCollectionOpened(CollectionOpenedEvent event) {
        String collectionName = event.getCollectionName();
        String collectionId = event.collection() != null ? event.collection().getId() : "unknown";

        log.info("Очищення кешів при відкритті колекції: {} (id: {})", collectionName, collectionId);

        try {
            // 1. Очищуємо кеш книг
            bookCache.clear();
            log.debug("BookCache очищено");

            // 2. Очищуємо кеш словників (автори, жанри, серії, групи)
            dictionaryCache.clearAll();
            log.debug("DictionaryCache очищено");

            // 3. Очищуємо кеш пошуку
            searchCache.clear();
            log.debug("SearchCache очищено");

            // 4. Очищуємо кеш обкладинок
            coverCache.clear();
            log.debug("CoverCache очищено");

            log.info("Всі кеші очищено для колекції: {}", collectionName);

        } catch (Exception e) {
            log.error("Помилка очищення кешів для колекції: {}", collectionName, e);
        }
    }
}