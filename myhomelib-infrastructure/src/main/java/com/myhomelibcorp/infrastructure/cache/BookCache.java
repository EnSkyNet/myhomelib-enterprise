package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
public class BookCache {

    private final Cache<BookId, Book> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build();

    public Optional<Book> get(BookId id) {
        return Optional.ofNullable(cache.getIfPresent(id));
    }

    public void put(BookId id, Book book) {
        if (book != null) {
            cache.put(id, book);
        }
    }

    public void evict(BookId id) {
        cache.invalidate(id);
    }

    public void clear() {
        cache.invalidateAll();
    }
}