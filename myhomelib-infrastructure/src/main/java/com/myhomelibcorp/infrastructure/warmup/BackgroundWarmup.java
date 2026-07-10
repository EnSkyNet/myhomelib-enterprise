package com.myhomelibcorp.infrastructure.warmup;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.infrastructure.cache.GlobalCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BackgroundWarmup {

    private final GlobalCache globalCache;
    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;

    /**
     * Явний виклик прогріву – запускається після вибору колекції.
     */
    public void warmup() {
        log.info("🔥 Starting background warmup...");
        try {
            // 1. Завантажуємо кеші в пам'ять
            globalCache.initialize();

            // 2. Прогріваємо підготовлені запити (можна додати)
            authorRepository.findAll();
            genreRepository.findAll();
            seriesRepository.findAll();

            log.info("✅ Background warmup completed");
        } catch (Exception e) {
            log.error("❌ Background warmup failed", e);
        }
    }
}