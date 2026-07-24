package com.myhomelibcorp.application.statistics;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatisticsService {

    private final StatisticsRepository statisticsRepository;

    @Cacheable(value = "libraryStatistics", unless = "#result == null")
    public LibraryStatistics getStatistics() {
        log.debug("Отримання статистики з репозиторію (кеш порожній)");
        return statisticsRepository.getStatistics();
    }

    @CacheEvict(value = "libraryStatistics", allEntries = true)
    public void refreshStatistics() {
        log.info("Очищення кешу статистики");
        statisticsRepository.refreshStatistics();
    }
}