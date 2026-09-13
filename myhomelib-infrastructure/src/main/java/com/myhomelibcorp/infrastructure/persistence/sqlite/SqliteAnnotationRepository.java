package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.AnnotationRepository;
import com.myhomelibcorp.domain.model.annotation.Annotation;
import com.myhomelibcorp.domain.model.annotation.AnnotationAnchor;
import com.myhomelibcorp.domain.model.annotation.AnnotationType;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.myhomelibcorp.infrastructure.persistence.CollectionTransactionExecutor;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class SqliteAnnotationRepository implements AnnotationRepository {
    private static final String BASE_SELECT = """
            SELECT a.id, a.book_id, a.artifact_id, a.annotation_type, a.color, a.note,
                   a.created_at, a.updated_at,
                   x.chapter_id, x.chapter_title, x.paragraph_id,
                   x.start_offset, x.end_offset, x.position,
                   x.quote_text, x.prefix_text, x.suffix_text
              FROM annotations a
              JOIN annotation_anchors x ON x.annotation_id=a.id
            """;

    private final QueryExecutor queryExecutor;
    private final CollectionManager collectionManager;

    @Override
    public Optional<Annotation> findById(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String normalizedId = id.trim();
        return CollectionTransactionExecutor.execute(collectionManager, () -> {
            AnnotationRow row = queryExecutor.query(BASE_SELECT + " WHERE a.id=? LIMIT 1", this::mapRow, normalizedId)
                    .stream().findFirst().orElse(null);
            if (row == null) return Optional.empty();
            return Optional.of(row.toAnnotation(tagsForIds(List.of(row.id()))));
        });
    }

    @Override
    public List<Annotation> findByBookId(String bookId) {
        if (bookId == null || bookId.isBlank()) return List.of();
        String normalizedBookId = bookId.trim();
        return CollectionTransactionExecutor.execute(collectionManager, () -> {
            List<AnnotationRow> rows = queryExecutor.query(
                    BASE_SELECT + " WHERE a.book_id=? ORDER BY a.updated_at DESC,a.id",
                    this::mapRow, normalizedBookId);
            if (rows.isEmpty()) return List.of();
            Map<String, Set<String>> tags = tagsForBook(normalizedBookId);
            return rows.stream().map(row -> row.toAnnotation(tags)).toList();
        });
    }

    @Override
    public Annotation save(Annotation annotation) {
        if (annotation == null) throw new IllegalArgumentException("annotation cannot be null");
        CollectionTransactionExecutor.run(collectionManager, () -> persist(annotation));
        return annotation;
    }

    private void persist(Annotation annotation) {
        AnnotationAnchor anchor = annotation.anchor();
        queryExecutor.update("""
                INSERT INTO annotations(id,book_id,artifact_id,annotation_type,color,note,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    book_id=excluded.book_id,artifact_id=excluded.artifact_id,
                    annotation_type=excluded.annotation_type,color=excluded.color,note=excluded.note,
                    created_at=excluded.created_at,updated_at=excluded.updated_at
                """,
                annotation.id(), anchor.bookId(), anchor.artifactId(), annotation.type().name(), annotation.color(),
                annotation.note(), annotation.createdAt().toString(), annotation.updatedAt().toString());
        queryExecutor.update("""
                INSERT INTO annotation_anchors(annotation_id,chapter_id,chapter_title,paragraph_id,start_offset,end_offset,
                    position,quote_text,prefix_text,suffix_text)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(annotation_id) DO UPDATE SET
                    chapter_id=excluded.chapter_id,chapter_title=excluded.chapter_title,paragraph_id=excluded.paragraph_id,
                    start_offset=excluded.start_offset,end_offset=excluded.end_offset,position=excluded.position,
                    quote_text=excluded.quote_text,prefix_text=excluded.prefix_text,suffix_text=excluded.suffix_text
                """,
                annotation.id(), anchor.chapterId(), anchor.chapterTitle(), anchor.paragraphId(),
                anchor.startOffset(), anchor.endOffset(), anchor.position(), anchor.quote(), anchor.prefix(), anchor.suffix());
        queryExecutor.update("DELETE FROM annotation_tags WHERE annotation_id=?", annotation.id());
        if (!annotation.tags().isEmpty()) {
            List<Object[]> batch = annotation.tags().stream().map(tag -> new Object[]{annotation.id(), tag}).toList();
            queryExecutor.batchUpdate("INSERT INTO annotation_tags(annotation_id,tag) VALUES(?,?)", batch);
        }
    }

    @Override
    public void deleteById(String id) {
        if (id == null || id.isBlank()) return;
        String normalizedId = id.trim();
        CollectionTransactionExecutor.run(collectionManager, () -> {
            // Do not depend on connection-local PRAGMA foreign_keys for user-data cleanup.
            queryExecutor.update("DELETE FROM annotation_tags WHERE annotation_id=?", normalizedId);
            queryExecutor.update("DELETE FROM annotation_anchors WHERE annotation_id=?", normalizedId);
            queryExecutor.update("DELETE FROM annotations WHERE id=?", normalizedId);
        });
    }

    @Override
    public long countByBookId(String bookId) {
        if (bookId == null || bookId.isBlank()) return 0L;
        return queryExecutor.queryForLong("SELECT COUNT(*) FROM annotations WHERE book_id=?", bookId.trim());
    }

    private Map<String, Set<String>> tagsForBook(String bookId) {
        List<TagRow> rows = queryExecutor.query("""
                SELECT t.annotation_id,t.tag
                  FROM annotation_tags t
                  JOIN annotations a ON a.id=t.annotation_id
                 WHERE a.book_id=?
                 ORDER BY t.annotation_id,t.tag COLLATE NOCASE
                """, (rs, rowNum) -> new TagRow(rs.getString(1), rs.getString(2)), bookId);
        return tagMap(rows);
    }

    private Map<String, Set<String>> tagsForIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        // Foundation lookup uses this path for one id. Keep SQL parameterized and bounded.
        List<TagRow> rows = queryExecutor.query(
                "SELECT annotation_id,tag FROM annotation_tags WHERE annotation_id=? ORDER BY tag COLLATE NOCASE",
                (rs, rowNum) -> new TagRow(rs.getString(1), rs.getString(2)), ids.getFirst());
        return tagMap(rows);
    }

    private static Map<String, Set<String>> tagMap(List<TagRow> rows) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (TagRow row : rows) result.computeIfAbsent(row.annotationId(), ignored -> new LinkedHashSet<>()).add(row.tag());
        return result;
    }

    private AnnotationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AnnotationRow(
                rs.getString("id"), rs.getString("book_id"), rs.getString("artifact_id"),
                AnnotationType.valueOf(rs.getString("annotation_type")), rs.getString("color"), rs.getString("note"),
                Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")),
                rs.getString("chapter_id"), rs.getString("chapter_title"), rs.getString("paragraph_id"),
                rs.getLong("start_offset"), rs.getLong("end_offset"), rs.getDouble("position"),
                rs.getString("quote_text"), rs.getString("prefix_text"), rs.getString("suffix_text"));
    }

    private record TagRow(String annotationId, String tag) { }

    private record AnnotationRow(String id, String bookId, String artifactId, AnnotationType type, String color,
                                 String note, Instant createdAt, Instant updatedAt, String chapterId,
                                 String chapterTitle, String paragraphId, long startOffset, long endOffset,
                                 double position, String quote, String prefix, String suffix) {
        private Annotation toAnnotation(Map<String, Set<String>> tags) {
            AnnotationAnchor anchor = new AnnotationAnchor(bookId, artifactId, chapterId, chapterTitle, paragraphId,
                    startOffset, endOffset, position, quote, prefix, suffix);
            return new Annotation(id, type, anchor, color, note, tags.getOrDefault(id, Set.of()), createdAt, updatedAt);
        }
    }
}
