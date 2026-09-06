package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.cache.AuthorCache;
import com.myhomelibcorp.application.port.out.cache.GenreCache;
import com.myhomelibcorp.application.port.out.cache.SeriesCache;
import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.cache.CaffeineSearchCache;
import com.myhomelibcorp.infrastructure.cache.CaffeineCoverCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CacheInvalidationAdapter implements CacheInvalidationPort {

    private final BookCache bookCache;
    private final CaffeineSearchCache searchCache;
    private final CaffeineCoverCache coverCache;
    private final AuthorCache authorCache;
    private final GenreCache genreCache;
    private final SeriesCache seriesCache;

    @Override
    public void invalidateAll() {
        log.info("🧹 Очищення всіх кешів");
        invalidateBookCache();
        invalidateReferenceCaches();
        invalidateSearchCache();
        invalidateCoverCache();
        log.info("✅ Всі кеші очищено");
    }

    @Override
    public void invalidateBookCache() {
        bookCache.clear();
        log.debug("BookCache очищено");
    }

    private void invalidateReferenceCaches() {
        authorCache.clear();
        genreCache.clear();
        seriesCache.clear();
        log.debug("Author/Genre/Series caches очищено");
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