package com.myhomelibcorp.application.port.out.cache;

import java.util.Optional;

/**
 * Minimal cache contract shared by entity repositories that implement cache-aside reads.
 * Domain-specific cache ports extend this interface so DI remains explicit and type-safe.
 */
public interface EntityCache<K, V> {
    Optional<V> get(K id);
    void put(K id, V value);
    void evict(K id);
    void clear();
}
