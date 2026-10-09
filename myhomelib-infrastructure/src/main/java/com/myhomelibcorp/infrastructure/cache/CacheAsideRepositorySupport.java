package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.EntityCache;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared cache-aside mechanics for repository decorators. */
final class CacheAsideRepositorySupport {
    private CacheAsideRepositorySupport() {
    }

    static <K, V> Optional<V> findById(
            K id,
            EntityCache<K, V> cache,
            Function<K, Optional<V>> loader) {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(loader, "loader");

        Optional<V> cached = cache.get(id);
        if (cached != null && cached.isPresent()) return cached;

        Optional<V> loaded = loader.apply(id);
        if (loaded == null || loaded.isEmpty()) return Optional.empty();
        V value = loaded.get();
        cache.put(id, value);
        return loaded;
    }

    static <K, V> V saveRequired(
            V value,
            EntityCache<K, V> cache,
            Function<V, V> saver,
            Function<V, K> idExtractor) {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(saver, "saver");
        Objects.requireNonNull(idExtractor, "idExtractor");

        V saved = saver.apply(value);
        cache.put(idExtractor.apply(saved), saved);
        return saved;
    }

    static <K, V> V saveIfIdentified(
            V value,
            EntityCache<K, V> cache,
            Function<V, V> saver,
            Function<V, K> idExtractor) {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(saver, "saver");
        Objects.requireNonNull(idExtractor, "idExtractor");

        V saved = saver.apply(value);
        if (saved != null) {
            K id = idExtractor.apply(saved);
            if (id != null) cache.put(id, saved);
        }
        return saved;
    }

    static <K, V> void deleteById(K id, EntityCache<K, V> cache, Consumer<K> deleter) {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(deleter, "deleter");
        deleter.accept(id);
        cache.evict(id);
    }
}
