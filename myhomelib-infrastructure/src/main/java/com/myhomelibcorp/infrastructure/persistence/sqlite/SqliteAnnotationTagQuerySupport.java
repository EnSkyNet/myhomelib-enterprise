package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared deterministic tag loading for annotation query adapters. */
final class SqliteAnnotationTagQuerySupport {
    private SqliteAnnotationTagQuerySupport() { }

    static Map<String, List<String>> load(QueryExecutor queryExecutor, List<String> annotationIds) {
        Objects.requireNonNull(queryExecutor, "queryExecutor");
        if (annotationIds == null || annotationIds.isEmpty()) return Map.of();

        String placeholders = String.join(",", Collections.nCopies(annotationIds.size(), "?"));
        List<TagRow> rows = queryExecutor.query(
                "SELECT annotation_id,tag FROM annotation_tags WHERE annotation_id IN (" + placeholders + ") "
                        + "ORDER BY annotation_id,tag COLLATE NOCASE",
                (rs, rowNum) -> new TagRow(rs.getString(1), rs.getString(2)),
                annotationIds.toArray());

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (TagRow row : rows) {
            result.computeIfAbsent(row.annotationId(), ignored -> new ArrayList<>()).add(row.tag());
        }
        result.replaceAll((id, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }

    private record TagRow(String annotationId, String tag) { }
}
