package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.Cache;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BookCache {

    private final CacheFactory cacheFactory;
    private Cache<BookId, Book> cache;

    @PostConstruct
    public void init() {
        this.cache = cacheFactory.createCache(10_000, 30);
    }

    public Optional<Book> get(BookId id) {
        return cache.get(id);
    }

    public void put(BookId id, Book book) {
        cache.put(id, book);
    }

    public void evict(BookId id) {
        cache.evict(id);
    }

    public void clear() {
        cache.clear();
    }
}