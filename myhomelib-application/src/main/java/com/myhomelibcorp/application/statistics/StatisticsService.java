package com.myhomelibcorp.application.statistics;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatisticsService {

    private final StatisticsRepository statisticsRepository;

    @Cacheable(value = "libraryStatistics", unless = "#result == null")
    public LibraryStatistics getStatistics() {
        return statisticsRepository.getStatistics();
    }

    public void refreshStatistics() {
        statisticsRepository.refreshStatistics();
        // Очистити кеш, якщо використовується Spring Cache
    }
}