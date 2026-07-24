package com.myhomelibcorp.infrastructure.warmup;

import com.myhomelibcorp.application.port.out.cache.DictionaryCachePort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BackgroundWarmup {

    private final DictionaryCachePort dictionaryCache;
    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;

    public void warmup() {
        log.info("🔥 Starting background warmup...");
        try {
            // Завантажуємо кеші, якщо вони ще не завантажені
            if (dictionaryCache.getAllAuthors().isEmpty()) {
                dictionaryCache.loadAuthors(authorRepository.findAll());
                dictionaryCache.loadGenres(genreRepository.findAll());
                dictionaryCache.loadSeries(seriesRepository.findAll());
                log.info("Кеші завантажено під час warmup");
            }

            log.info("✅ Background warmup completed");
        } catch (Exception e) {
            log.error("❌ Background warmup failed", e);
        }
    }
}