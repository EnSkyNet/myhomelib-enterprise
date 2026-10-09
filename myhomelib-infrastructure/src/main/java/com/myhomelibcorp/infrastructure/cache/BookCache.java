package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.application.port.out.cache.EntityCache;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Memory-bounded cache for hydrated Book aggregates. */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookCache implements EntityCache<BookId, Book> {
    private static final int MAX_WEIGHT_KIB = 24 * 1024; // ~24 MiB total budget; avoids duplicate retained book aggregates

    private final CollectionManager collectionManager;
    private final com.github.benmanes.caffeine.cache.Cache<String, Book> cache = Caffeine.newBuilder()
            .maximumWeight(MAX_WEIGHT_KIB)
            .weigher((String key, Book book) -> estimateWeightKiB(book))
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .recordStats()
            .build();

    private String buildKey(BookId id) {
        Collection current = collectionManager.getCurrentCollection();
        String collectionId = current != null && current.getId() != null && !current.getId().isBlank()
                ? current.getId().trim()
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
        if (id == null) return;
        evictKeys(List.of(buildKey(id)));
    }

    /**
     * Evicts several books in the active collection with one transaction synchronization.
     *
     * <p>Repository mutations can call this before their surrounding transaction commits. An
     * immediate invalidation alone has a race: another reader may repopulate the old committed row
     * before commit, leaving that stale aggregate cached afterwards. Therefore the same captured
     * collection-scoped keys are invalidated once more AFTER_COMMIT.</p>
     */
    public void evictAll(java.util.Collection<BookId> ids) {
        if (ids == null || ids.isEmpty()) return;
        List<String> keys = ids.stream()
                .filter(java.util.Objects::nonNull)
                .map(this::buildKey)
                .distinct()
                .toList();
        evictKeys(keys);
    }

    private void evictKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        cache.invalidateAll(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            // Capture exact keys now. A later collection switch must never redirect an after-commit
            // callback to another collection's cache namespace.
            List<String> committedKeys = List.copyOf(keys);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.invalidateAll(committedKeys);
                }
            });
        }
    }

    /**
     * Evicts one logical book from every collection scope. Required for asynchronous domain
     * events whose listener may execute after the active collection has already changed.
     */
    public void evictAllScopes(BookId id) {
        if (id == null) return;
        String suffix = ":" + id.asString();
        cache.asMap().keySet().removeIf(key -> key != null && key.endsWith(suffix));
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
        for (var artifact : book.getArtifacts()) {
            if (artifact == null) continue;
            bytes += 192 + stringBytes(artifact.getId()) + stringBytes(artifact.getName())
                    + stringBytes(artifact.getFormat()) + stringBytes(artifact.getMediaType())
                    + stringBytes(artifact.getSha256()) + stringBytes(artifact.getContentFingerprint());
            if (artifact.getFile() != null) {
                bytes += stringBytes(artifact.getFile().getFileName())
                        + stringBytes(artifact.getFile().getFolder())
                        + stringBytes(artifact.getFile().getArchiveEntry())
                        + stringBytes(artifact.getFile().getCollectionRoot());
            }
            for (var entry : artifact.getMetadata().entrySet()) {
                bytes += 64 + stringBytes(entry.getKey()) + stringBytes(entry.getValue());
            }
        }
        if (book.getCover() != null && book.getCover().getData() != null) bytes += book.getCover().getData().length;
        long kib = Math.max(1L, (bytes + 1023L) / 1024L);
        return (int) Math.min(MAX_WEIGHT_KIB, kib);
    }

    private static long stringBytes(String value) {
        return value == null ? 0L : 40L + (long) value.length() * 2L;
    }
}
