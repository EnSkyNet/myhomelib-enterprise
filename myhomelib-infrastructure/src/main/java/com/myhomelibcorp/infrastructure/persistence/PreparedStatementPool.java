package com.myhomelibcorp.infrastructure.persistence;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Пул підготовлених запитів для перевикористання.
 * Зберігає PreparedStatement за ключем (SQL-рядок).
 * Автоматично закриває при зміні колекції.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PreparedStatementPool {

    private final CollectionManager collectionManager;
    private final Map<String, PreparedStatement> cache = new ConcurrentHashMap<>();

    /**
     * Отримує PreparedStatement для заданого SQL.
     * Якщо він уже створений – повертає з кешу, інакше створює новий.
     */
    public PreparedStatement getOrCreate(String sql) throws SQLException {
        return cache.computeIfAbsent(sql, key -> {
            try {
                Connection conn = collectionManager.getCurrentDataSource().getConnection();
                return conn.prepareStatement(key);
            } catch (SQLException e) {
                log.error("Failed to create PreparedStatement for: {}", sql, e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Інвалідує всі запити (наприклад, при зміні схеми або переключенні колекції).
     */
    public void invalidateAll() {
        for (PreparedStatement ps : cache.values()) {
            try {
                ps.close();
            } catch (SQLException e) {
                log.warn("Error closing PreparedStatement", e);
            }
        }
        cache.clear();
        log.info("PreparedStatement pool cleared");
    }

    /**
     * Інвалідує конкретний запит.
     */
    public void invalidate(String sql) {
        PreparedStatement ps = cache.remove(sql);
        if (ps != null) {
            try {
                ps.close();
            } catch (SQLException e) {
                log.warn("Error closing PreparedStatement for: {}", sql, e);
            }
        }
    }
}