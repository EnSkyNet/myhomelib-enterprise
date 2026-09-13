package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.annotation.AnnotationManagerFacets;
import com.myhomelibcorp.application.annotation.AnnotationManagerFilter;
import com.myhomelibcorp.application.annotation.AnnotationManagerItem;
import com.myhomelibcorp.application.annotation.AnnotationManagerPage;
import com.myhomelibcorp.application.annotation.AnnotationManagerType;
import com.myhomelibcorp.application.port.out.annotation.AnnotationManagerQueryPort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.myhomelibcorp.infrastructure.persistence.CollectionTransactionExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQLite-backed, bounded query adapter for the global Annotation Manager. */
@Repository
@RequiredArgsConstructor
public class SqliteAnnotationManagerQueryAdapter implements AnnotationManagerQueryPort {
    private static final Pattern FTS_TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final String FROM = """
            FROM annotations a
            JOIN annotation_anchors x ON x.annotation_id=a.id
            LEFT JOIN books b ON b.id=a.book_id
            JOIN annotation_search_fts ON annotation_search_fts.annotation_id=a.id
            """;

    private final QueryExecutor queryExecutor;
    private final CollectionManager collectionManager;

    @Override
    public AnnotationManagerPage query(AnnotationManagerFilter filter, int offset, int limit) {
        AnnotationManagerFilter effective = filter == null ? AnnotationManagerFilter.empty() : filter;
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(500, limit));
        return CollectionTransactionExecutor.execute(collectionManager, () -> {
            SqlFilter sqlFilter = buildFilter(effective);
            long total = queryExecutor.queryForLong("SELECT COUNT(*) " + FROM + sqlFilter.where(), sqlFilter.params().toArray());
            List<Row> rows = queryExecutor.query("""
                    SELECT a.id,a.book_id,COALESCE(b.title,''),a.annotation_type,a.color,a.note,
                           x.chapter_title,x.quote_text,x.position,a.created_at,a.updated_at
                    """ + FROM + sqlFilter.where() + " ORDER BY a.updated_at DESC,a.id LIMIT ? OFFSET ?",
                    this::mapRow, append(sqlFilter.params(), safeLimit, safeOffset));
            Map<String, List<String>> tags = tagsFor(rows.stream().map(Row::id).toList());
            List<AnnotationManagerItem> items = rows.stream().map(row -> row.toItem(tags.getOrDefault(row.id(), List.of()))).toList();
            return new AnnotationManagerPage(items, total, safeOffset, safeLimit);
        });
    }

    @Override
    public AnnotationManagerFacets facets() {
        return CollectionTransactionExecutor.execute(collectionManager, () -> {
            List<AnnotationManagerFacets.BookFacet> books = queryExecutor.query("""
                    SELECT DISTINCT a.book_id,COALESCE(b.title,'')
                      FROM annotations a
                      LEFT JOIN books b ON b.id=a.book_id
                     ORDER BY COALESCE(b.title,'') COLLATE NOCASE,a.book_id
                    """, (rs, rowNum) -> new AnnotationManagerFacets.BookFacet(rs.getString(1), rs.getString(2)));
            List<String> colors = queryExecutor.query("""
                    SELECT DISTINCT color FROM annotations
                     WHERE color IS NOT NULL AND TRIM(color)<>''
                     ORDER BY color COLLATE NOCASE
                    """, (rs, rowNum) -> rs.getString(1));
            List<String> tags = queryExecutor.query("""
                    SELECT DISTINCT tag FROM annotation_tags
                     WHERE tag IS NOT NULL AND TRIM(tag)<>''
                     ORDER BY tag COLLATE NOCASE
                    """, (rs, rowNum) -> rs.getString(1));
            return new AnnotationManagerFacets(books, colors, tags);
        });
    }

    private SqlFilter buildFilter(AnnotationManagerFilter filter) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (filter.searchText() != null) {
            String ftsQuery = toFtsQuery(filter.searchText());
            if (!ftsQuery.isBlank()) {
                clauses.add("annotation_search_fts MATCH ?");
                params.add(ftsQuery);
            }
            String like = "%" + escapeLike(filter.searchText().toLowerCase(Locale.ROOT)) + "%";
            clauses.add("""
                    (LOWER(COALESCE(b.title,'')) LIKE ? ESCAPE '\\'
                     OR LOWER(COALESCE(a.note,'')) LIKE ? ESCAPE '\\'
                     OR LOWER(COALESCE(x.quote_text,'')) LIKE ? ESCAPE '\\'
                     OR LOWER(COALESCE(x.chapter_title,'')) LIKE ? ESCAPE '\\'
                     OR EXISTS (SELECT 1 FROM annotation_tags st WHERE st.annotation_id=a.id
                                AND LOWER(st.tag) LIKE ? ESCAPE '\\'))
                    """);
            for (int i = 0; i < 5; i++) params.add(like);
        }
        if (filter.bookId() != null) {
            clauses.add("a.book_id=?");
            params.add(filter.bookId());
        }
        if (filter.type() != null) {
            clauses.add("a.annotation_type=?");
            params.add(filter.type().name());
        }
        if (filter.color() != null) {
            clauses.add("UPPER(a.color)=?");
            params.add(filter.color());
        }
        if (filter.tag() != null) {
            clauses.add("EXISTS (SELECT 1 FROM annotation_tags ft WHERE ft.annotation_id=a.id AND LOWER(ft.tag)=LOWER(?))");
            params.add(filter.tag());
        }
        if (filter.dateFrom() != null) {
            clauses.add("substr(a.updated_at,1,10)>=?");
            params.add(filter.dateFrom().toString());
        }
        if (filter.dateTo() != null) {
            clauses.add("substr(a.updated_at,1,10)<=?");
            params.add(filter.dateTo().toString());
        }
        return new SqlFilter(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }

    private Map<String, List<String>> tagsFor(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<TagRow> rows = queryExecutor.query(
                "SELECT annotation_id,tag FROM annotation_tags WHERE annotation_id IN (" + placeholders + ") "
                        + "ORDER BY annotation_id,tag COLLATE NOCASE",
                (rs, rowNum) -> new TagRow(rs.getString(1), rs.getString(2)), ids.toArray());
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (TagRow row : rows) {
            result.computeIfAbsent(row.annotationId(), ignored -> new ArrayList<>()).add(row.tag());
        }
        result.replaceAll((id, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }

    private Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getString(1), rs.getString(2), rs.getString(3), AnnotationManagerType.valueOf(rs.getString(4)),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getDouble(9),
                Instant.parse(rs.getString(10)), Instant.parse(rs.getString(11)));
    }

    private static String toFtsQuery(String value) {
        if (value == null || value.isBlank()) return "";
        Matcher matcher = FTS_TOKEN.matcher(value.toLowerCase(Locale.ROOT));
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (!token.isBlank()) tokens.add("\"" + token.replace("\"", "\"\"") + "\"");
        }
        return String.join(" AND ", tokens);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Object[] append(List<Object> params, Object... tail) {
        Object[] result = new Object[params.size() + tail.length];
        for (int i = 0; i < params.size(); i++) result[i] = params.get(i);
        System.arraycopy(tail, 0, result, params.size(), tail.length);
        return result;
    }

    private record SqlFilter(String where, List<Object> params) { }
    private record TagRow(String annotationId, String tag) { }
    private record Row(String id, String bookId, String bookTitle, AnnotationManagerType type, String color,
                       String note, String chapterTitle, String quote, double position,
                       Instant createdAt, Instant updatedAt) {
        private AnnotationManagerItem toItem(List<String> tags) {
            return new AnnotationManagerItem(id, bookId, bookTitle, type, color, note, tags, chapterTitle, quote,
                    position, createdAt, updatedAt);
        }
    }
}
