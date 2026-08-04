package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.Cache;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookCache {

    private final CacheFactory cacheFactory;
    private final CollectionManager collectionManager;
    private Cache<String, Book> cache;

    @PostConstruct
    public void init() {
        this.cache = cacheFactory.createCache(10_000, 30);
        log.info("BookCache ініціалізовано: maxSize=10000, expireMinutes=30");
    }

    private String buildKey(BookId id) {
        String collectionId = collectionManager.getCurrentCollection() != null
                ? collectionManager.getCurrentCollection().getId()
                : "default";
        return collectionId + ":" + id.asString();
    }

    public Optional<Book> get(BookId id) {
        if (id == null) {
            return Optional.empty();
        }
        return cache.get(buildKey(id));
    }

    public void put(BookId id, Book book) {
        if (id != null && book != null) {
            cache.put(buildKey(id), book);
            log.trace("Книгу додано до кешу: {}", id);
        }
    }

    public void evict(BookId id) {
        if (id != null) {
            cache.evict(buildKey(id));
            log.trace("Книгу видалено з кешу: {}", id);
        }
    }

    public void clear() {
        cache.clear();
        log.debug("Кеш книг очищено");
    }
}