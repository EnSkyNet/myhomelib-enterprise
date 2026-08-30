package com.myhomelibcorp.infrastructure.integrity;

import com.myhomelibcorp.application.port.out.integrity.DataIntegrityPort;
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

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public IntegrityReport checkIntegrity() {
        List<String> issues = new ArrayList<>();

        // 1. Книги без авторів
        long booksWithoutAuthor = countBooksWithoutAuthor();
        if (booksWithoutAuthor > 0) {
            issues.add("Знайдено " + booksWithoutAuthor + " книг без авторів");
        }

        // 2. Книги без жанрів
        long booksWithoutGenre = countBooksWithoutGenre();
        if (booksWithoutGenre > 0) {
            issues.add("Знайдено " + booksWithoutGenre + " книг без жанрів");
        }

        // 3. Автори без книг
        long orphanedAuthors = countOrphanedAuthors();
        if (orphanedAuthors > 0) {
            issues.add("Знайдено " + orphanedAuthors + " авторів без книг");
        }

        // 4. Жанри без книг
        long orphanedGenres = countOrphanedGenres();
        if (orphanedGenres > 0) {
            issues.add("Знайдено " + orphanedGenres + " жанрів без книг");
        }

        // 5. Physical catalog duplicates. Count in SQL and keep only a tiny sample in memory.
        DuplicateScan duplicateScan = scanPhysicalDuplicates();
        long duplicateBooks = duplicateScan.count();
        if (duplicateBooks > 0) {
            issues.add("Знайдено " + duplicateBooks + " фізичних дублікатів книг");
            duplicateScan.sampleIds().forEach(id -> log.debug("Дублікат ID: {}", id));
        }

        return new IntegrityReport(issues, booksWithoutAuthor, booksWithoutGenre,
                orphanedAuthors, orphanedGenres, duplicateBooks);
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