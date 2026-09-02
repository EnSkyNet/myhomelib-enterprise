package com.myhomelibcorp.application.statistics;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatisticsService {

    private final StatisticsRepository statisticsRepository;

    public LibraryStatistics getStatistics() {
        log.debug("Отримання collection-aware статистики з активної БД");
        return statisticsRepository.getStatistics();
    }

    public void invalidate() {
        statisticsRepository.invalidate();
    }

    public void refreshStatistics() {
        log.info("Перерахунок статистики активної колекції");
        statisticsRepository.refreshStatistics();
    }
}