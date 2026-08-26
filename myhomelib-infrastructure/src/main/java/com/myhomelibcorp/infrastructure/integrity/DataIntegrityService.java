package com.myhomelibcorp.infrastructure.integrity;

import com.myhomelibcorp.application.port.out.integrity.DataIntegrityPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.usecase.integrity.IntegrityReport;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataIntegrityService implements DataIntegrityPort {

    private final BookQueryRepository bookRepository;
    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;
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

        // 5. Дублікати книг
        List<BookId> duplicateIds = findDuplicateBookIds();
        long duplicateBooks = duplicateIds.size();
        if (duplicateBooks > 0) {
            issues.add("Знайдено " + duplicateBooks + " дублікатів книг");
            duplicateIds.stream().limit(10).forEach(id ->
                    log.debug("Дублікат ID: {}", id)
            );
        }

        return new IntegrityReport(issues, booksWithoutAuthor, booksWithoutGenre,
                orphanedAuthors, orphanedGenres, duplicateBooks);
    }

    @Override
    public void fixOrphanedData() {
        throw new UnsupportedOperationException(
                "Legacy destructive integrity repair is disabled. Use CollectionMaintenanceUseCase "
                        + "for analyze -> dry-run -> mandatory backup -> explicit apply.");
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

    private List<BookId> findDuplicateBookIds() {
        List<String> idStrings = findDuplicateBookIdStrings();
        return idStrings.stream().map(BookId::fromString).collect(Collectors.toList());
    }

    private List<String> findDuplicateBookIdStrings() {
        String sql = """
                SELECT b.id
                FROM books b
                WHERE EXISTS (
                    SELECT 1
                    FROM books b2
                    JOIN book_authors ba2 ON b2.id = ba2.book_id
                    JOIN authors a2 ON ba2.author_id = a2.id
                    WHERE b2.id != b.id
                      AND b2.title = b.title
                      AND a2.last_name = (
                          SELECT a.last_name
                          FROM book_authors ba
                          JOIN authors a ON ba.author_id = a.id
                          WHERE ba.book_id = b.id
                          LIMIT 1
                      )
                      AND b2.id < b.id
                )
                """;
        return getJdbcTemplate().query(sql, (rs, rowNum) -> rs.getString("id"));
    }
}