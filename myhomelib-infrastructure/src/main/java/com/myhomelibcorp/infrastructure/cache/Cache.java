package com.myhomelibcorp.infrastructure.cache;

import java.util.Optional;
import java.util.function.Supplier;

public interface Cache<K, V> {

    /**
     * Повертає кешоване значення або null, якщо відсутнє.
     */
    Optional<V> get(K key);

    /**
     * Зберігає значення в кеші.
     */
    void put(K key, V value);

    /**
     * Видаляє значення з кешу.
     */
    void evict(K key);

    /**
     * Очищує весь кеш.
     */
    void clear();

    /**
     * Повертає значення з кешу, а якщо відсутнє — завантажує через supplier.
     */
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