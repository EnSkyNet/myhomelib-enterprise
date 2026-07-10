package com.myhomelibcorp.infrastructure.statistics;

import com.myhomelibcorp.application.dto.LibraryStatistics;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteStatisticsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class StatisticsEngine {

    private final SqliteStatisticsRepository statisticsRepository;

    public void refreshAfterImport() {
        log.info("🔄 Updating library statistics after import...");
        statisticsRepository.refreshStatistics();
        log.info("✅ Statistics updated");
    }

    public void logStatistics() {
        LibraryStatistics stats = statisticsRepository.getStatistics();
        if (stats != null) {
            log.info("📊 Library stats: books={}, authors={}, genres={}, series={}, groups={}",
                    stats.getBooksCount(), stats.getAuthorsCount(),
                    stats.getGenresCount(), stats.getSeriesCount(), 0);
        }
    }
}