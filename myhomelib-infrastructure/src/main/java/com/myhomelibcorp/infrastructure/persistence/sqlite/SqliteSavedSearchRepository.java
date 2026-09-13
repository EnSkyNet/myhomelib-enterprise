package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.domain.model.search.SavedSearch;
import com.myhomelibcorp.domain.model.search.SavedSearchKind;
import com.myhomelibcorp.domain.model.search.SmartCollectionSpec;
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
    private final ObjectMapper objectMapper;

    private final RowMapper<SavedSearch> rowMapper = (rs, rowNum) -> {
        String id = rs.getString("id");
        String name = rs.getString("name");
        String query = rs.getString("query");
        String filters = rs.getString("filters");
        LocalDateTime createdAt = LocalDateTime.parse(rs.getString("created_at"));
        LocalDateTime lastUsed = LocalDateTime.parse(rs.getString("last_used"));
        int useCount = rs.getInt("use_count");
        SavedSearchKind kind = parseKind(rs.getString("kind"));
        boolean pinned = rs.getInt("pinned") != 0;
        SmartCollectionSpec spec = kind == SavedSearchKind.SMART_COLLECTION ? decodeSmartCollection(filters) : null;
        return SavedSearch.restore(id, name, query, filters, createdAt, lastUsed, useCount, kind, pinned, spec);
    };

    @Override
    public List<SavedSearch> findAll() {
        String sql = "SELECT * FROM saved_searches ORDER BY pinned DESC, name COLLATE NOCASE, id";
        return queryExecutor.query(sql, rowMapper);
    }

    @Override
    public Optional<SavedSearch> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM saved_searches WHERE id = ? LIMIT 1";
        return queryExecutor.query(sql, rowMapper, id).stream().findFirst();
    }

    @Override
    public Optional<SavedSearch> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String sql = "SELECT * FROM saved_searches WHERE name = ? LIMIT 1";
        return queryExecutor.query(sql, rowMapper, name.trim()).stream().findFirst();
    }

    @Override
    public SavedSearch save(SavedSearch search) {
        if (search == null) throw new IllegalArgumentException("search cannot be null");
        String filters = encodedFilters(search);
        if (findById(search.getId()).isPresent()) {
            queryExecutor.update("""
                    UPDATE saved_searches
                       SET name=?, query=?, filters=?, last_used=?, use_count=?, kind=?, pinned=?
                     WHERE id=?
                    """,
                    search.getName(), search.getQuery(), filters,
                    search.getLastUsed().toString(), search.getUseCount(), search.getKind().name(), search.isPinned() ? 1 : 0,
                    search.getId());
            return findById(search.getId()).orElse(search);
        }

        queryExecutor.update("""
                INSERT INTO saved_searches
                    (id, name, query, filters, created_at, last_used, use_count, kind, pinned)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                search.getId(), search.getName(), search.getQuery(), filters,
                search.getCreatedAt().toString(), search.getLastUsed().toString(), search.getUseCount(),
                search.getKind().name(), search.isPinned() ? 1 : 0);
        return search;
    }

    @Override
    public void deleteById(String id) {
        if (id == null || id.isBlank()) return;
        queryExecutor.update("DELETE FROM saved_searches WHERE id = ?", id);
    }

    private String encodedFilters(SavedSearch search) {
        if (!search.isSmartCollection()) return search.getFilters();
        try {
            return objectMapper.writeValueAsString(search.getSmartCollection());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize smart collection " + search.getName(), e);
        }
    }

    private SmartCollectionSpec decodeSmartCollection(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Smart collection row has no definition");
        }
        try {
            return objectMapper.readValue(json, SmartCollectionSpec.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize smart collection definition", e);
        }
    }

    private static SavedSearchKind parseKind(String value) {
        if (value == null || value.isBlank()) return SavedSearchKind.SEARCH;
        try {
            return SavedSearchKind.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            log.warn("Unknown saved search kind '{}'; falling back to SEARCH", value);
            return SavedSearchKind.SEARCH;
        }
    }
}
