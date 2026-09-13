package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.annotation.export.AnnotationExportPage;
import com.myhomelibcorp.application.annotation.export.AnnotationExportRow;
import com.myhomelibcorp.application.annotation.export.AnnotationExportSelection;
import com.myhomelibcorp.application.port.out.annotation.AnnotationExportQueryPort;
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
import java.util.Map;

/** SQLite implementation for deterministic, bounded rich annotation export. */
@Repository
@RequiredArgsConstructor
public class SqliteAnnotationExportQueryAdapter implements AnnotationExportQueryPort {
    private static final String FROM = """
            FROM annotations a
            JOIN annotation_anchors x ON x.annotation_id=a.id
            LEFT JOIN books b ON b.id=a.book_id
            """;

    private final QueryExecutor queryExecutor;
    private final CollectionManager collectionManager;

    @Override
    public AnnotationExportPage query(AnnotationExportSelection selection, int offset, int limit) {
        AnnotationExportSelection effective = selection == null ? AnnotationExportSelection.all() : selection;
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(500, limit));
        return CollectionTransactionExecutor.execute(collectionManager, () -> {
            SqlSelection where = selectionSql(effective);
            List<Object> pageParams = new ArrayList<>(where.params());
            pageParams.add(safeLimit + 1);
            pageParams.add(safeOffset);
            List<Row> rows = queryExecutor.query("""
                    SELECT a.id,a.book_id,COALESCE(b.title,''),
                           COALESCE((SELECT GROUP_CONCAT(author_name, ', ') FROM (
                                      SELECT TRIM(COALESCE(au.first_name,'') || ' ' || COALESCE(au.middle_name || ' ','') || COALESCE(au.last_name,'')) AS author_name
                                        FROM book_authors ba JOIN authors au ON au.id=ba.author_id
                                       WHERE ba.book_id=a.book_id
                                       ORDER BY au.last_name COLLATE NOCASE,au.first_name COLLATE NOCASE,au.middle_name COLLATE NOCASE,au.id
                           )),''),
                           COALESCE(b.series,''),COALESCE(b.language,''),COALESCE(b.file_name,''),COALESCE(b.isbn,''),
                           a.annotation_type,COALESCE(a.color,''),COALESCE(x.chapter_id,''),COALESCE(x.chapter_title,''),
                           COALESCE(x.quote_text,''),COALESCE(a.note,''),x.position,a.created_at,a.updated_at
                    """ + FROM + where.where() + """
                     ORDER BY COALESCE(b.title,'') COLLATE NOCASE,a.book_id,a.created_at,a.id
                     LIMIT ? OFFSET ?
                    """, this::mapRow, pageParams.toArray());
            boolean hasNext = rows.size() > safeLimit;
            if (hasNext) rows = new ArrayList<>(rows.subList(0, safeLimit));
            Map<String, List<String>> tags = tagsFor(rows.stream().map(Row::id).toList());
            List<AnnotationExportRow> items = rows.stream()
                    .map(row -> row.toExportRow(tags.getOrDefault(row.id(), List.of())))
                    .toList();
            return new AnnotationExportPage(items, safeOffset, safeLimit, hasNext);
        });
    }

    private SqlSelection selectionSql(AnnotationExportSelection selection) {
        if (selection.allBooks()) return new SqlSelection("", List.of());
        List<String> ids = selection.bookIds().stream().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return new SqlSelection(" WHERE a.book_id IN (" + placeholders + ")", new ArrayList<>(ids));
    }

    private Map<String, List<String>> tagsFor(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<TagRow> rows = queryExecutor.query(
                "SELECT annotation_id,tag FROM annotation_tags WHERE annotation_id IN (" + placeholders + ") " +
                        "ORDER BY annotation_id,tag COLLATE NOCASE",
                (rs, rowNum) -> new TagRow(rs.getString(1), rs.getString(2)), ids.toArray());
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (TagRow row : rows) result.computeIfAbsent(row.annotationId(), ignored -> new ArrayList<>()).add(row.tag());
        result.replaceAll((id, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }

    private Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getDouble(15),
                Instant.parse(rs.getString(16)), Instant.parse(rs.getString(17)));
    }

    private record SqlSelection(String where, List<Object> params) { }
    private record TagRow(String annotationId, String tag) { }
    private record Row(String id, String bookId, String bookTitle, String authors, String series, String language,
                       String fileName, String isbn, String type, String color, String chapterId, String chapterTitle,
                       String quote, String note, double position, Instant createdAt, Instant updatedAt) {
        AnnotationExportRow toExportRow(List<String> tags) {
            return new AnnotationExportRow(id, bookId, bookTitle, authors, series, language, fileName, isbn, type,
                    color, chapterId, chapterTitle, quote, note, tags, position, createdAt, updatedAt);
        }
    }
}
