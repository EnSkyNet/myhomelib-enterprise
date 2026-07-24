package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.Cache;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Кеш для книг. Використовує Caffeine для зберігання об'єктів Book за їх ID.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookCache {

    private final CacheFactory cacheFactory;
    private Cache<BookId, Book> cache;

    @PostConstruct
    public void init() {
        this.cache = cacheFactory.createCache(10_000, 30);
        log.info("BookCache ініціалізовано: maxSize=10000, expireMinutes=30");
    }

    /**
     * Отримує книгу з кешу за ID.
     */
    public Optional<Book> get(BookId id) {
        if (id == null) {
            return Optional.empty();
        }
        return cache.get(id);
    }

    /**
     * Зберігає книгу в кеші.
     */
    public void put(BookId id, Book book) {
        if (id != null && book != null) {
            cache.put(id, book);
            log.trace("Книгу додано до кешу: {}", id);
        }
    }

    /**
     * Видаляє книгу з кешу за ID.
     */
    public void evict(BookId id) {
        if (id != null) {
            cache.evict(id);
            log.trace("Книгу видалено з кешу: {}", id);
        }
    }

    /**
     * Очищує весь кеш книг.
     */
    public void clear() {
        cache.clear();
        log.debug("Кеш книг очищено");
    }
}