package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class SearchCache {

    private final Map<String, List<BookId>> cache = new ConcurrentHashMap<>();

    public List<BookId> get(String query) {
        if (query == null) return null;
        return cache.get(query.toLowerCase().trim());
    }

    public void put(String query, List<BookId> ids) {
        if (query == null || ids == null) return;
        cache.put(query.toLowerCase().trim(), ids);
    }

    public void evict(String query) {
        if (query == null) return;
        cache.remove(query.toLowerCase().trim());
    }

    public void clear() {
        cache.clear();
        log.debug("Кеш пошуку очищено");
    }
}