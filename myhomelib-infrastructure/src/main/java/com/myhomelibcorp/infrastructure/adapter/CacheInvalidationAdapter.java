package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.cache.DictionaryCache;
import com.myhomelibcorp.infrastructure.cache.SearchCache;
import com.myhomelibcorp.infrastructure.cache.CoverCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CacheInvalidationAdapter implements CacheInvalidationPort {

    private final BookCache bookCache;
    private final DictionaryCache dictionaryCache;
    private final SearchCache searchCache;
    private final CoverCache coverCache;

    @Override
    public void invalidateAll() {
        log.info("🧹 Очищення всіх кешів");
        invalidateBookCache();
        invalidateDictionaryCache();
        invalidateSearchCache();
        invalidateCoverCache();
        log.info("✅ Всі кеші очищено");
    }

    @Override
    public void invalidateBookCache() {
        bookCache.clear();
        log.debug("BookCache очищено");
    }

    @Override
    public void invalidateDictionaryCache() {
        dictionaryCache.clearAll();
        log.debug("DictionaryCache очищено");
    }

    @Override
    public void invalidateSearchCache() {
        searchCache.clear();
        log.debug("SearchCache очищено");
    }

    @Override
    public void invalidateCoverCache() {
        coverCache.clear();
        log.debug("CoverCache очищено");
    }
}