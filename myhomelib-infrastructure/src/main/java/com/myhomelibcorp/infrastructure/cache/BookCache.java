package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Memory-bounded cache for hydrated Book aggregates. */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookCache {
    private static final int MAX_WEIGHT_KIB = 64 * 1024; // ~64 MiB total budget

    private final CollectionManager collectionManager;
    private final com.github.benmanes.caffeine.cache.Cache<String, Book> cache = Caffeine.newBuilder()
            .maximumWeight(MAX_WEIGHT_KIB)
            .weigher((String key, Book book) -> estimateWeightKiB(book))
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .recordStats()
            .build();

    private String buildKey(BookId id) {
        String collectionId = collectionManager.getCurrentCollection() != null
                ? collectionManager.getCurrentCollection().getId()
                : "default";
        return collectionId + ":" + id.asString();
    }

    public Optional<Book> get(BookId id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(cache.getIfPresent(buildKey(id)));
    }

    public void put(BookId id, Book book) {
        if (id != null && book != null) cache.put(buildKey(id), book);
    }

    public void evict(BookId id) {
        if (id != null) cache.invalidate(buildKey(id));
    }

    public void clear() {
        cache.invalidateAll();
        log.debug("Кеш книг очищено");
    }

    private static int estimateWeightKiB(Book book) {
        if (book == null) return 1;
        long bytes = 512; // aggregate/object/list overhead approximation
        bytes += stringBytes(book.getTitle());
        bytes += stringBytes(book.getSeries());
        bytes += stringBytes(book.getFileName());
        bytes += stringBytes(book.getFolder());
        bytes += stringBytes(book.getArchiveEntry());
        bytes += stringBytes(book.getCollectionRoot());
        bytes += stringBytes(book.getAnnotation());
        bytes += stringBytes(book.getKeywords());
        bytes += stringBytes(book.getReview());
        bytes += stringBytes(book.getPublisher());
        bytes += stringBytes(book.getLibId());
        bytes += stringBytes(book.getTranslators());
        bytes += stringBytes(book.getCity());
        bytes += stringBytes(book.getSourceUrl());
        for (var author : book.getAuthors()) if (author != null) bytes += 128 + stringBytes(author.getFullName());
        for (var genre : book.getGenres()) if (genre != null) bytes += 96 + stringBytes(genre.getName());
        if (book.getCover() != null && book.getCover().getData() != null) bytes += book.getCover().getData().length;
        long kib = Math.max(1L, (bytes + 1023L) / 1024L);
        return (int) Math.min(MAX_WEIGHT_KIB, kib);
    }

    private static long stringBytes(String value) {
        return value == null ? 0L : 40L + (long) value.length() * 2L;
    }
}
