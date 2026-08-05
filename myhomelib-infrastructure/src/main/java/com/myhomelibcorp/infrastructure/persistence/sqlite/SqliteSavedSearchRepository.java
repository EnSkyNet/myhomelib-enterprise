package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.domain.model.search.SavedSearch;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteSavedSearchRepository implements SavedSearchRepository {

    private final QueryExecutor queryExecutor;
    private final CollectionManager collectionManager;

    private final RowMapper<SavedSearch> rowMapper = (rs, rowNum) -> {
        String id = rs.getString("id");
        String name = rs.getString("name");
        String query = rs.getString("query");
        String filters = rs.getString("filters");
        LocalDateTime createdAt = LocalDateTime.parse(rs.getString("created_at"));
        LocalDateTime lastUsed = LocalDateTime.parse(rs.getString("last_used"));
        int useCount = rs.getInt("use_count");

        SavedSearch search = new SavedSearch(name, query, filters);
        // Використовуємо рефлексію для встановлення полів, які не мають сеттерів
        try {
            var idField = SavedSearch.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(search, id);

            var createdAtField = SavedSearch.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(search, createdAt);

            var lastUsedField = SavedSearch.class.getDeclaredField("lastUsed");
            lastUsedField.setAccessible(true);
            lastUsedField.set(search, lastUsed);

            var useCountField = SavedSearch.class.getDeclaredField("useCount");
            useCountField.setAccessible(true);
            useCountField.set(search, useCount);
        } catch (Exception e) {
            log.warn("Помилка встановлення полів SavedSearch", e);
        }
        return search;
    };

    @Override
    public List<SavedSearch> findAll() {
        String sql = "SELECT * FROM saved_searches ORDER BY name";
        return queryExecutor.query(sql, rowMapper);
    }

    @Override
    public Optional<SavedSearch> findById(String id) {
        String sql = "SELECT * FROM saved_searches WHERE id = ?";
        try {
            SavedSearch search = queryExecutor.queryForObject(sql, rowMapper, id);
            return Optional.of(search);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<SavedSearch> findByName(String name) {
        String sql = "SELECT * FROM saved_searches WHERE name = ?";
        try {
            SavedSearch search = queryExecutor.queryForObject(sql, rowMapper, name);
            return Optional.of(search);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public SavedSearch save(SavedSearch search) {
        // Перевіряємо, чи існує запис
        Optional<SavedSearch> existing = findByName(search.getName());

        if (existing.isPresent()) {
            // Оновлюємо
            String sql = """
                UPDATE saved_searches 
                SET query = ?, filters = ?, last_used = ?, use_count = ?
                WHERE name = ?
                """;
            queryExecutor.update(sql,
                    search.getQuery(),
                    search.getFilters(),
                    search.getLastUsed().toString(),
                    search.getUseCount(),
                    search.getName()
            );
            return findByName(search.getName()).orElse(search);
        } else {
            // Вставляємо новий
            String sql = """
                INSERT INTO saved_searches (id, name, query, filters, created_at, last_used, use_count)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
            queryExecutor.update(sql,
                    search.getId(),
                    search.getName(),
                    search.getQuery(),
                    search.getFilters(),
                    search.getCreatedAt().toString(),
                    search.getLastUsed().toString(),
                    search.getUseCount()
            );
            return search;
        }
    }

    @Override
    public void deleteById(String id) {
        String sql = "DELETE FROM saved_searches WHERE id = ?";
        queryExecutor.update(sql, id);
    }

    @Override
    public void deleteByName(String name) {
        String sql = "DELETE FROM saved_searches WHERE name = ?";
        queryExecutor.update(sql, name);
    }

    @Override
    public List<SavedSearch> findRecent(int limit) {
        String sql = "SELECT * FROM saved_searches ORDER BY last_used DESC LIMIT ?";
        return queryExecutor.query(sql, rowMapper, limit);
    }

    @Override
    public List<SavedSearch> findMostUsed(int limit) {
        String sql = "SELECT * FROM saved_searches ORDER BY use_count DESC LIMIT ?";
        return queryExecutor.query(sql, rowMapper, limit);
    }
}