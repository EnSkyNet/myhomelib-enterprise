package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.CacheRefreshPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CacheRefreshServiceImpl implements CacheRefreshPort {

    private final DictionaryCache dictionaryCache;
    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;

    @Async("taskExecutor")
    @Override
    public void refreshCachesAsync() {
        log.info("Асинхронне оновлення кешів словників");
        try {
            dictionaryCache.loadAuthors(authorRepository.findAll());
            dictionaryCache.loadGenres(genreRepository.findAll());
            dictionaryCache.loadSeries(seriesRepository.findAll());
            log.info("Кеші словників оновлено асинхронно");
        } catch (Exception e) {
            log.error("Помилка асинхронного оновлення кешів", e);
        }
    }

    @Override
    public void refreshCachesSync() {
        log.info("Синхронне оновлення кешів словників");
        try {
            dictionaryCache.loadAuthors(authorRepository.findAll());
            dictionaryCache.loadGenres(genreRepository.findAll());
            dictionaryCache.loadSeries(seriesRepository.findAll());
            log.info("Кеші словників оновлено синхронно");
        } catch (Exception e) {
            log.error("Помилка синхронного оновлення кешів", e);
        }
    }
}