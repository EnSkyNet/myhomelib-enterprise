package com.myhomelibcorp.infrastructure.integrity;

import com.myhomelibcorp.application.port.out.integrity.DataIntegrityPort;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.usecase.integrity.IntegrityReport;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataIntegrityService implements DataIntegrityPort {

    private final CollectionManager collectionManager;
    private final SearchIndexer searchIndexer;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public IntegrityReport checkIntegrity() {
        List<String> issues = new ArrayList<>();

        long booksWithoutAuthor = countBooksWithoutAuthor();
        if (booksWithoutAuthor > 0) issues.add("Знайдено " + booksWithoutAuthor + " книг без авторів");

        long booksWithoutGenre = countBooksWithoutGenre();
        if (booksWithoutGenre > 0) issues.add("Знайдено " + booksWithoutGenre + " книг без жанрів");

        long orphanedAuthors = countOrphanedAuthors();
        if (orphanedAuthors > 0) issues.add("Знайдено " + orphanedAuthors + " авторів без книг");

        long orphanedGenres = countOrphanedGenres();
        if (orphanedGenres > 0) issues.add("Знайдено " + orphanedGenres + " жанрів без книг");

        DuplicateScan duplicateScan = scanPhysicalDuplicates();
        long duplicateBooks = duplicateScan.count();
        if (duplicateBooks > 0) {
            issues.add("Знайдено " + duplicateBooks + " фізичних дублікатів книг");
            duplicateScan.sampleIds().forEach(id -> log.debug("Дублікат ID: {}", id));
        }

        long orphanedSeries = countOrphanedSeries();
        if (orphanedSeries > 0) issues.add("Знайдено " + orphanedSeries + " серій без книг");

        long booksWithMissingSeries = countBooksWithMissingSeries();
        if (booksWithMissingSeries > 0) issues.add("Знайдено " + booksWithMissingSeries + " книг із серіями, відсутніми у довіднику");

        long brokenRelations = countBrokenRelations();
        if (brokenRelations > 0) issues.add("Знайдено " + brokenRelations + " пошкоджених зв’язків book_authors/book_genres");

        SqliteIntegrity sqlite = checkSqliteIntegrity();
        if (!sqlite.ok()) issues.add("SQLite integrity_check: " + sqlite.message());

        long catalogBooks = countIndexableBooks();
        long luceneDocuments = -1L;
        boolean luceneOk = false;
        try {
            luceneDocuments = searchIndexer.getDocumentCount();
            luceneOk = luceneDocuments == catalogBooks;
            if (!luceneOk) {
                issues.add("Lucene не відповідає SQLite: документів " + luceneDocuments + ", книг " + catalogBooks);
            }
        } catch (RuntimeException error) {
            issues.add("Не вдалося перевірити Lucene: " + rootMessage(error));
        }

        return new IntegrityReport(issues, booksWithoutAuthor, booksWithoutGenre, orphanedAuthors, orphanedGenres,
                duplicateBooks, orphanedSeries, booksWithMissingSeries, brokenRelations, sqlite.ok(), sqlite.message(),
                luceneOk, catalogBooks, luceneDocuments);
    }


    // ==================== ПРИВАТНІ МЕТОДИ ====================

    private long countBooksWithoutAuthor() {
        String sql = """
                SELECT COUNT(*) FROM books b
                WHERE NOT EXISTS (
                    SELECT 1 FROM book_authors ba WHERE ba.book_id = b.id
                )
                """;
        return getJdbcTemplate().queryForObject(sql, Long.class);
    }

    private long countBooksWithoutGenre() {
        String sql = """
                SELECT COUNT(*) FROM books b
                WHERE NOT EXISTS (
                    SELECT 1 FROM book_genres bg WHERE bg.book_id = b.id
                )
                """;
        return getJdbcTemplate().queryForObject(sql, Long.class);
    }

    private long countOrphanedAuthors() {
        String sql = """
                SELECT COUNT(*) FROM authors a
                WHERE NOT EXISTS (
                    SELECT 1 FROM book_authors ba WHERE ba.author_id = a.id
                )
                """;
        return getJdbcTemplate().queryForObject(sql, Long.class);
    }

    private long countOrphanedGenres() {
        String sql = """
                SELECT COUNT(*) FROM genres g
                WHERE NOT EXISTS (
                    SELECT 1 FROM book_genres bg WHERE bg.genre_code = g.code
                )
                """;
        return getJdbcTemplate().queryForObject(sql, Long.class);
    }

    private long countOrphanedSeries() {
        Long count = getJdbcTemplate().queryForObject("""
                SELECT COUNT(*) FROM series s
                WHERE NOT EXISTS (
                    SELECT 1 FROM books b
                    WHERE TRIM(COALESCE(b.series, '')) <> ''
                      AND LOWER(TRIM(b.series)) = LOWER(TRIM(s.name))
                )
                """, Long.class);
        return count == null ? 0L : count;
    }

    private long countBooksWithMissingSeries() {
        Long count = getJdbcTemplate().queryForObject("""
                SELECT COUNT(*) FROM books b
                WHERE TRIM(COALESCE(b.series, '')) <> ''
                  AND NOT EXISTS (
                    SELECT 1 FROM series s WHERE LOWER(TRIM(s.name)) = LOWER(TRIM(b.series))
                  )
                """, Long.class);
        return count == null ? 0L : count;
    }

    private long countBrokenRelations() {
        Long authorLinks = getJdbcTemplate().queryForObject("""
                SELECT COUNT(*) FROM book_authors ba
                LEFT JOIN books b ON b.id = ba.book_id
                LEFT JOIN authors a ON a.id = ba.author_id
                WHERE b.id IS NULL OR a.id IS NULL
                """, Long.class);
        Long genreLinks = getJdbcTemplate().queryForObject("""
                SELECT COUNT(*) FROM book_genres bg
                LEFT JOIN books b ON b.id = bg.book_id
                LEFT JOIN genres g ON g.code = bg.genre_code
                WHERE b.id IS NULL OR g.code IS NULL
                """, Long.class);
        return (authorLinks == null ? 0L : authorLinks) + (genreLinks == null ? 0L : genreLinks);
    }

    private SqliteIntegrity checkSqliteIntegrity() {
        List<String> rows = getJdbcTemplate().query("PRAGMA integrity_check", (rs, rowNum) -> rs.getString(1));
        if (rows.size() == 1 && "ok".equalsIgnoreCase(rows.getFirst().trim())) return new SqliteIntegrity(true, "ok");
        String message = rows.isEmpty() ? "немає результату" : String.join("; ", rows.stream().limit(10).toList());
        return new SqliteIntegrity(false, message);
    }

    private long countIndexableBooks() {
        Long count = getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM books WHERE COALESCE(deleted, 0) = 0", Long.class);
        return count == null ? 0L : count;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) current = current.getCause();
        if (current == null) return "невідома помилка";
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record SqliteIntegrity(boolean ok, String message) { }

    /**
     * Uses the same stable physical identity as maintenance/statistics instead of an O(N²)-like
     * correlated title/author search. User-visible logical editions with the same title are not
     * treated as corruption merely because their metadata happens to match.
     */
    private DuplicateScan scanPhysicalDuplicates() {
        String groups = """
                FROM books
                WHERE COALESCE(deleted, 0) = 0
                  AND TRIM(COALESCE(lib_id, '')) <> ''
                GROUP BY lib_id,
                         COALESCE(collection_root, ''),
                         COALESCE(folder, ''),
                         COALESCE(file_name, ''),
                         COALESCE(archive_entry, '')
                HAVING COUNT(*) > 1
                """;
        Long count = getJdbcTemplate().queryForObject(
                "SELECT COALESCE(SUM(cnt - 1), 0) FROM (SELECT COUNT(*) AS cnt " + groups + ")",
                Long.class);
        long duplicateCount = count == null ? 0L : count;
        if (duplicateCount == 0) return new DuplicateScan(0, List.of());

        String sampleSql = """
                WITH duplicate_groups AS (
                    SELECT lib_id,
                           COALESCE(collection_root, '') AS collection_root,
                           COALESCE(folder, '') AS folder,
                           COALESCE(file_name, '') AS file_name,
                           COALESCE(archive_entry, '') AS archive_entry,
                           MIN(id) AS keep_id
                    """ + groups + """
                )
                SELECT b.id
                FROM books b
                JOIN duplicate_groups d
                  ON b.lib_id = d.lib_id
                 AND COALESCE(b.collection_root, '') = d.collection_root
                 AND COALESCE(b.folder, '') = d.folder
                 AND COALESCE(b.file_name, '') = d.file_name
                 AND COALESCE(b.archive_entry, '') = d.archive_entry
                WHERE COALESCE(b.deleted, 0) = 0
                  AND b.id <> d.keep_id
                LIMIT 10
                """;
        List<String> sample = getJdbcTemplate().query(sampleSql, (rs, rowNum) -> rs.getString("id"));
        return new DuplicateScan(duplicateCount, List.copyOf(sample));
    }

    private record DuplicateScan(long count, List<String> sampleIds) { }

}