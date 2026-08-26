package com.myhomelibcorp.infrastructure.opds;

import com.myhomelibcorp.application.opds.OpdsBookDto;
import com.myhomelibcorp.application.opds.OpdsBookQuery;
import com.myhomelibcorp.application.opds.OpdsFacetDto;
import com.myhomelibcorp.application.opds.OpdsPage;
import com.myhomelibcorp.application.port.out.opds.OpdsCatalogQueryPort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite read-only OPDS projection. Every public collection query is bounded by LIMIT/OFFSET;
 * no route materializes the complete author/series/genre/book catalogue.
 */
@Repository
@RequiredArgsConstructor
public class SqliteOpdsCatalogQueryAdapter implements OpdsCatalogQueryPort {

    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MIN_LIMIT = 1;

    private final CollectionManager collectionManager;

    private JdbcTemplate jdbc() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public OpdsPage<OpdsFacetDto> authors(int offset, int limit) {
        int safeLimit = clamp(limit);
        long total = scalar("""
                SELECT COUNT(*) FROM authors a
                WHERE EXISTS (
                    SELECT 1 FROM book_authors ba 
                    JOIN books b ON b.id = ba.book_id
                    WHERE ba.author_id = a.id AND b.deleted = 0)
                """);

        String sql = """
                SELECT a.id AS id, 
                       %s AS label, 
                       COUNT(DISTINCT b.id) AS book_count
                FROM authors a
                JOIN book_authors ba ON ba.author_id = a.id
                JOIN books b ON b.id = ba.book_id AND b.deleted = 0
                GROUP BY a.id
                ORDER BY label, a.id
                LIMIT ? OFFSET ?
                """.formatted(getAuthorFullNameExpression());

        List<OpdsFacetDto> items = jdbc().query(sql, (rs, row) -> {
            String label = nonBlank(rs.getString("label"), "Без імені");
            return new OpdsFacetDto(
                    rs.getString("id"),
                    label,
                    rs.getLong("book_count")
            );
        }, safeLimit, safeOffset(offset));

        return new OpdsPage<>(items, total, safeOffset(offset), safeLimit);
    }

    @Override
    public OpdsPage<OpdsFacetDto> series(int offset, int limit) {
        int safeLimit = clamp(limit);
        long total = scalar("""
                SELECT COUNT(DISTINCT LOWER(TRIM(series))) 
                FROM books 
                WHERE deleted = 0 
                  AND TRIM(COALESCE(series,'')) <> ''
                """);

        String sql = """
                SELECT LOWER(TRIM(series)) AS id,
                       MIN(TRIM(series)) AS label,
                       COUNT(*) AS book_count
                FROM books
                WHERE deleted = 0
                  AND TRIM(COALESCE(series,'')) <> ''
                GROUP BY LOWER(TRIM(series))
                ORDER BY LOWER(TRIM(series))
                LIMIT ? OFFSET ?
                """;

        List<OpdsFacetDto> items = jdbc().query(sql, (rs, row) -> {
            String label = nonBlank(rs.getString("label"), "Без назви");
            return new OpdsFacetDto(
                    rs.getString("id"),
                    label,
                    rs.getLong("book_count")
            );
        }, safeLimit, safeOffset(offset));

        return new OpdsPage<>(items, total, safeOffset(offset), safeLimit);
    }

    @Override
    public OpdsPage<OpdsFacetDto> genres(int offset, int limit) {
        int safeLimit = clamp(limit);
        long total = scalar("""
                SELECT COUNT(*) FROM genres g
                WHERE EXISTS (
                    SELECT 1 FROM book_genres bg 
                    JOIN books b ON b.id = bg.book_id
                    WHERE bg.genre_code = g.code AND b.deleted = 0)
                """);

        String sql = """
                SELECT g.code AS id, 
                       COALESCE(NULLIF(TRIM(g.name),''), g.code) AS label,
                       COUNT(DISTINCT b.id) AS book_count
                FROM genres g
                JOIN book_genres bg ON bg.genre_code = g.code
                JOIN books b ON b.id = bg.book_id AND b.deleted = 0
                GROUP BY g.code
                ORDER BY label, g.code
                LIMIT ? OFFSET ?
                """;

        List<OpdsFacetDto> items = jdbc().query(sql, (rs, row) -> {
            String label = nonBlank(rs.getString("label"), rs.getString("id"));
            return new OpdsFacetDto(
                    rs.getString("id"),
                    label,
                    rs.getLong("book_count")
            );
        }, safeLimit, safeOffset(offset));

        return new OpdsPage<>(items, total, safeOffset(offset), safeLimit);
    }

    @Override
    public OpdsPage<OpdsBookDto> books(OpdsBookQuery query) {
        OpdsBookQuery q = query == null ? OpdsBookQuery.all(0, DEFAULT_LIMIT) : query;
        List<Object> params = new ArrayList<>();
        String where = buildWhereClause(q, params);
        long total = countBooks(where, params);

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(q.limit());
        pageParams.add(q.offset());

        String sql = """
                SELECT b.id, b.title, b.series, b.language, b.year, b.annotation,
                       COALESCE(b.format, '') AS format, b.local, b.file_name, b.archive_entry,
                       COALESCE((
                           SELECT group_concat(%s, ', ')
                           FROM book_authors ba 
                           JOIN authors a ON a.id = ba.author_id
                           WHERE ba.book_id = b.id
                       ), '') AS authors
                FROM books b
                %s
                ORDER BY b.title, b.id
                LIMIT ? OFFSET ?
                """.formatted(getAuthorFullNameExpression(), where);

        List<OpdsBookDto> items = jdbc().query(sql, (rs, row) -> {
            String title = nonBlank(rs.getString("title"), "Без назви");
            String series = rs.getString("series");
            return new OpdsBookDto(
                    rs.getString("id"),
                    title,
                    rs.getString("authors"),
                    series != null ? series : "",
                    rs.getString("language"),
                    nullableInt(rs, "year"),
                    rs.getString("annotation"),
                    rs.getString("format"),
                    rs.getInt("local") == 1,
                    rs.getString("file_name"),
                    rs.getString("archive_entry")
            );
        }, pageParams.toArray());

        return new OpdsPage<>(items, total, q.offset(), q.limit());
    }

    @Override
    public Optional<OpdsBookDto> book(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return Optional.empty();
        }

        String sql = """
                SELECT b.id, b.title, b.series, b.language, b.year, b.annotation,
                       COALESCE(b.format, '') AS format, b.local, b.file_name, b.archive_entry,
                       COALESCE((SELECT group_concat(%s, ', ')
                           FROM book_authors ba 
                           JOIN authors a ON a.id = ba.author_id 
                           WHERE ba.book_id = b.id), '') AS authors
                FROM books b 
                WHERE b.deleted = 0 AND b.id = ? 
                LIMIT 1
                """.formatted(getAuthorFullNameExpression());

        List<OpdsBookDto> items = jdbc().query(sql, (rs, row) -> {
            String title = nonBlank(rs.getString("title"), "Без назви");
            String series = rs.getString("series");
            return new OpdsBookDto(
                    rs.getString("id"),
                    title,
                    rs.getString("authors"),
                    series != null ? series : "",
                    rs.getString("language"),
                    nullableInt(rs, "year"),
                    rs.getString("annotation"),
                    rs.getString("format"),
                    rs.getInt("local") == 1,
                    rs.getString("file_name"),
                    rs.getString("archive_entry")
            );
        }, bookId);

        return items.stream().findFirst();
    }

    // --- Helper methods ---

    private String getAuthorFullNameExpression() {
        return """
            TRIM(
                COALESCE(a.last_name,'') || 
                CASE WHEN TRIM(COALESCE(a.first_name,'')) <> '' 
                     THEN ' ' || a.first_name 
                     ELSE '' 
                END || 
                CASE WHEN TRIM(COALESCE(a.middle_name,'')) <> '' 
                     THEN ' ' || a.middle_name 
                     ELSE '' 
                END
            )
            """;
    }

    private String buildWhereClause(OpdsBookQuery q, List<Object> params) {
        StringBuilder where = new StringBuilder("WHERE b.deleted = 0");

        if (!q.authorId().isBlank()) {
            where.append(" AND EXISTS (");
            where.append("SELECT 1 FROM book_authors oba ");
            where.append("WHERE oba.book_id = b.id AND oba.author_id = ?)");
            params.add(q.authorId());
        }

        if (!q.series().isBlank()) {
            where.append(" AND LOWER(TRIM(COALESCE(b.series,''))) = LOWER(?)");
            params.add(q.series().trim());
        }

        if (!q.genreCode().isBlank()) {
            where.append(" AND EXISTS (");
            where.append("SELECT 1 FROM book_genres obg ");
            where.append("WHERE obg.book_id = b.id AND obg.genre_code = ?)");
            params.add(q.genreCode());
        }

        if (!q.text().isBlank()) {
            String needle = "%" + q.text().toLowerCase(java.util.Locale.ROOT) + "%";
            where.append(" AND (");
            where.append("LOWER(COALESCE(b.title,'')) LIKE ? OR ");
            where.append("LOWER(COALESCE(b.series,'')) LIKE ? OR ");
            where.append("LOWER(COALESCE(b.keywords,'')) LIKE ? OR ");
            where.append("LOWER(COALESCE(b.annotation,'')) LIKE ? OR ");
            where.append("EXISTS (");
            where.append("SELECT 1 FROM book_authors oba ");
            where.append("JOIN authors oa ON oa.id = oba.author_id ");
            where.append("WHERE oba.book_id = b.id AND ");
            where.append("LOWER(COALESCE(oa.last_name,'') || ' ' || ");
            where.append("COALESCE(oa.first_name,'') || ' ' || ");
            where.append("COALESCE(oa.middle_name,'')) LIKE ?)");
            where.append(")");

            for (int i = 0; i < 5; i++) {
                params.add(needle);
            }
        }

        return where.toString();
    }

    private long countBooks(String where, List<Object> params) {
        Long value = jdbc().queryForObject(
                "SELECT COUNT(*) FROM books b " + where,
                Long.class,
                params.toArray()
        );
        return value == null ? 0L : value;
    }

    private long scalar(String sql) {
        Long value = jdbc().queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private static int clamp(int limit) {
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, limit));
    }

    private static int safeOffset(int offset) {
        return Math.max(0, offset);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}