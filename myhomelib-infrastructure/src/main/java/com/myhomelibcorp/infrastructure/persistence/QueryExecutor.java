package com.myhomelibcorp.infrastructure.persistence;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueryExecutor {

    private final CollectionManager collectionManager;

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
        return getJdbcTemplate().update(sql, params);
    }

    public int[] batchUpdate(String sql, List<Object[]> batchArgs) {
        if (batchArgs == null || batchArgs.isEmpty()) {
            return new int[0];
        }
        return getJdbcTemplate().batchUpdate(sql, batchArgs);
    }

    public void execute(String sql) {
        getJdbcTemplate().execute(sql);
    }
}