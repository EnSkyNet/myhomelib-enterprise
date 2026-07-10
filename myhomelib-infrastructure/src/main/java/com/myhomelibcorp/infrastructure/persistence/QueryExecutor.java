package com.myhomelibcorp.infrastructure.persistence;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueryExecutor {

    private final CollectionManager collectionManager;
    private final PreparedStatementPool psPool;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... params) {
        return getJdbcTemplate().query(sql, rowMapper, params);
    }

    public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... params) {
        return getJdbcTemplate().queryForObject(sql, rowMapper, params);
    }

    public <T> T queryForObject(String sql, Class<T> requiredType, Object... params) {
        return getJdbcTemplate().queryForObject(sql, requiredType, params);
    }

    public long queryForLong(String sql, Object... params) {
        Long result = getJdbcTemplate().queryForObject(sql, Long.class, params);
        return result != null ? result : 0L;
    }

    public int update(String sql, Object... params) {
        try {
            PreparedStatement ps = psPool.getOrCreate(sql);
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute update: " + sql, e);
        }
    }

    /**
     * Виконує батчеве оновлення з використанням JdbcTemplate.
     * Повертає масив кількостей оновлених рядків для кожного запиту.
     */
    public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
        if (batchArgs == null || batchArgs.isEmpty()) {
            return new int[0];
        }
        return getJdbcTemplate().batchUpdate(sql, batchArgs);
    }

    /**
     * Виконує SQL-команду без параметрів (наприклад, PRAGMA, DDL).
     */
    public void execute(String sql) {
        getJdbcTemplate().execute(sql);
    }
}