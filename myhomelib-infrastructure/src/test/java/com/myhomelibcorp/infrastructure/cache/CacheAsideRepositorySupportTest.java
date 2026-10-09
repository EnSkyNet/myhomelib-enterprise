package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.EntityCache;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CacheAsideRepositorySupportTest {

    @Test
    void cacheHitDoesNotCallDelegate() {
        MapCache cache = new MapCache();
        cache.put("id", "cached");
        AtomicInteger loads = new AtomicInteger();

        Optional<String> result = CacheAsideRepositorySupport.findById("id", cache, id -> {
            loads.incrementAndGet();
            return Optional.of("db");
        });

        assertEquals(Optional.of("cached"), result);
        assertEquals(0, loads.get());
    }

    @Test
    void cacheMissLoadsAndPopulatesCache() {
        MapCache cache = new MapCache();

        Optional<String> result = CacheAsideRepositorySupport.findById(
                "id", cache, id -> Optional.of("db"));

        assertEquals(Optional.of("db"), result);
        assertEquals(Optional.of("db"), cache.get("id"));
    }

    @Test
    void deleteRunsDelegateBeforeEviction() {
        MapCache cache = new MapCache();
        cache.put("id", "cached");
        AtomicInteger delegateCalls = new AtomicInteger();

        CacheAsideRepositorySupport.deleteById("id", cache, id -> {
            assertEquals(Optional.of("cached"), cache.get(id));
            delegateCalls.incrementAndGet();
        });

        assertEquals(1, delegateCalls.get());
        assertTrue(cache.get("id").isEmpty());
    }

    @Test
    void nullableSavePreservesAuthorStyleNullSemantics() {
        MapCache cache = new MapCache();
        String saved = CacheAsideRepositorySupport.saveIfIdentified(
                "input", cache, value -> null, value -> value);
        assertNull(saved);
        assertTrue(cache.values.isEmpty());
    }

    private static final class MapCache implements EntityCache<String, String> {
        private final Map<String, String> values = new HashMap<>();

        @Override public Optional<String> get(String id) { return Optional.ofNullable(values.get(id)); }
        @Override public void put(String id, String value) { values.put(id, value); }
        @Override public void evict(String id) { values.remove(id); }
        @Override public void clear() { values.clear(); }
    }
}
