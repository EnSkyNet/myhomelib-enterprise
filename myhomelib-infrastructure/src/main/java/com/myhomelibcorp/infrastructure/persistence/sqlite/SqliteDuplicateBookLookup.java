package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.DuplicateBookCandidate;
import com.myhomelibcorp.application.port.out.repository.DuplicateBookLookup;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SqliteDuplicateBookLookup implements DuplicateBookLookup {

    /** 3 bind parameters per candidate; stays below conservative SQLite variable limits. */
    private static final int CANDIDATES_PER_QUERY = 120;

    private final CollectionManager collectionManager;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public Map<DuplicateBookCandidate, BookId> findDuplicateIds(List<DuplicateBookCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return Map.of();

        LinkedHashMap<DuplicateBookCandidate, BookId> result = new LinkedHashMap<>();
        for (int from = 0; from < candidates.size(); from += CANDIDATES_PER_QUERY) {
            int to = Math.min(candidates.size(), from + CANDIDATES_PER_QUERY);
            resolveChunk(candidates.subList(from, to), result);
        }
        return result;
    }

    private void resolveChunk(List<DuplicateBookCandidate> chunk,
                              Map<DuplicateBookCandidate, BookId> target) {
        StringBuilder values = new StringBuilder(chunk.size() * 10);
        List<Object> params = new ArrayList<>(chunk.size() * 3);
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) values.append(',');
            values.append("(?,?,?)");
            DuplicateBookCandidate candidate = chunk.get(i);
            params.add(i);
            params.add(candidate.title());
            params.add(candidate.firstAuthorLastName());
        }

        String sql = """
                WITH candidates(ord, title, last_name) AS (VALUES %s)
                SELECT c.ord, MIN(b.id) AS book_id
                FROM candidates c
                JOIN books b ON b.title = c.title
                JOIN book_authors ba ON ba.book_id = b.id
                JOIN authors a ON a.id = ba.author_id AND a.last_name = c.last_name
                GROUP BY c.ord
                ORDER BY c.ord
                """.formatted(values);

        getJdbcTemplate().query(sql, rs -> {
            int ordinal = rs.getInt("ord");
            String rawId = rs.getString("book_id");
            if (rawId != null && ordinal >= 0 && ordinal < chunk.size()) {
                target.putIfAbsent(chunk.get(ordinal), BookId.fromString(rawId));
            }
        }, params.toArray());
    }
}
