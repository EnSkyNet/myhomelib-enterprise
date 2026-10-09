package com.myhomelibcorp.infrastructure.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Shared typed cache mechanics for collection-local domain objects. */
final class CollectionScopedCaffeineCache<K, V> {
    private final CollectionManager collectionManager;
    private final Function<K, String> localKey;
    private final Cache<String, V> cache;

    CollectionScopedCaffeineCache(CollectionManager collectionManager,
                                  long maximumSize,
                                  Duration expireAfterWrite,
                                  Function<K, String> localKey) {
        if (maximumSize <= 0) throw new IllegalArgumentException("maximumSize must be > 0");
        this.collectionManager = collectionManager;
        this.localKey = Objects.requireNonNull(localKey, "localKey");
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(Objects.requireNonNull(expireAfterWrite, "expireAfterWrite"))
                .recordStats()
                .build();
    }

    Optional<V> get(K key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(cache.getIfPresent(scopedKey(key)));
    }

    void put(K key, V value) {
        if (key != null && value != null) cache.put(scopedKey(key), value);
    }

    void evict(K key) {
        if (key != null) cache.invalidate(scopedKey(key));
    }

    void clear() {
        cache.invalidateAll();
    }

    private String scopedKey(K key) {
        return CollectionScopedCacheKey.of(collectionManager, localKey.apply(key));
    }
}
