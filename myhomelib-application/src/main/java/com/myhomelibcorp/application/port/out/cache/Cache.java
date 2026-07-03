package com.myhomelibcorp.application.port.out.cache;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Уніфікований інтерфейс кешу.
 * @param <K> тип ключа
 * @param <V> тип значення
 */
public interface Cache<K, V> {

    Optional<V> get(K key);

    void put(K key, V value);

    void evict(K key);

    void clear();

    default V getOrLoad(K key, Supplier<V> loader) {
        return get(key).orElseGet(() -> {
            V value = loader.get();
            if (value != null) {
                put(key, value);
            }
            return value;
        });
    }
}