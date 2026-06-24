package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.port.out.SeriesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeriesServiceImpl implements SeriesService {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<String> getAllSeriesNames() {
        log.info("🔍 getAllSeriesNames() invoked");
        try {
            // Перевіряємо загальну кількість книг
            String countSql = "SELECT COUNT(*) FROM books";
            int totalBooks = jdbcTemplate.queryForObject(countSql, Integer.class);
            log.info("📊 У БД всього книг: {}", totalBooks);

            // Перевіряємо, чи є книги з заповненою серією
            String hasSeriesSql = "SELECT COUNT(*) FROM books WHERE series IS NOT NULL AND TRIM(series) != ''";
            int booksWithSeries = jdbcTemplate.queryForObject(hasSeriesSql, Integer.class);
            log.info("📊 Книг з серією: {}", booksWithSeries);

            // Отримуємо унікальні назви серій
            String sql = "SELECT DISTINCT TRIM(series) FROM books WHERE series IS NOT NULL AND TRIM(series) != '' ORDER BY TRIM(series)";
            List<String> result = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString(1));
            log.info("✅ Завантажено {} серій з БД", result.size());
            if (!result.isEmpty()) {
                log.info("📋 Перші 3 серії: {}", result.subList(0, Math.min(3, result.size())));
            } else {
                log.warn("⚠️ Серій не знайдено, хоча книг з серією: {}", booksWithSeries);
            }
            return result;
        } catch (Exception e) {
            log.error("❌ Помилка завантаження серій", e);
            return List.of();
        }
    }
}