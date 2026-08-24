package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class JdbcBatchWriter {

    private final CollectionManager collectionManager;
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    /**
     * Вставляє батч книг з колонкою collection_root.
     */
    public void batchInsertFull(List<Object[]> booksData,
                                Map<String, String> authorCache,
                                Map<String, String> genreCache) {
        if (booksData.isEmpty()) return;

        JdbcTemplate jt = getJdbcTemplate();

        String insertBookSql = """
            INSERT INTO books (
                id, title, series, sequence_number, file_name, folder,
                archive_entry, language, file_size, keywords, annotation,
                rate, progress, update_date, isbn, deleted, local,
                review, created_at, collection_root, year, publisher,
                lib_id, library_rate, translators, city, source_url
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                series = excluded.series,
                sequence_number = excluded.sequence_number,
                file_name = CASE WHEN books.local = 1 THEN books.file_name ELSE excluded.file_name END,
                folder = CASE WHEN books.local = 1 THEN books.folder ELSE excluded.folder END,
                archive_entry = CASE WHEN books.local = 1 THEN books.archive_entry ELSE excluded.archive_entry END,
                language = excluded.language,
                file_size = CASE WHEN books.local = 1 THEN books.file_size ELSE excluded.file_size END,
                keywords = excluded.keywords,
                annotation = excluded.annotation,
                rate = books.rate,
                progress = books.progress,
                update_date = excluded.update_date,
                isbn = excluded.isbn,
                deleted = excluded.deleted,
                local = CASE WHEN books.local = 1 THEN 1 ELSE excluded.local END,
                review = books.review,
                created_at = books.created_at,
                collection_root = CASE WHEN books.local = 1 THEN books.collection_root ELSE excluded.collection_root END,
                year = excluded.year,
                publisher = excluded.publisher,
                lib_id = CASE WHEN COALESCE(excluded.lib_id,'') <> '' THEN excluded.lib_id ELSE books.lib_id END,
                library_rate = excluded.library_rate,
                translators = excluded.translators,
                city = excluded.city,
                source_url = CASE WHEN COALESCE(excluded.source_url,'') <> '' THEN excluded.source_url ELSE books.source_url END
            """;

        List<Object[]> bookBatch = new ArrayList<>();
        List<Object[]> authorLinkBatch = new ArrayList<>();
        List<Object[]> genreLinkBatch = new ArrayList<>();

        for (Object[] row : booksData) {
            Object[] bookRow = new Object[27];
            System.arraycopy(row, 0, bookRow, 0, 19);
            bookRow[19] = row[21]; // collection_root
            bookRow[20] = row[22]; // year
            bookRow[21] = row[23]; // publisher
            bookRow[22] = row[24]; // lib_id
            bookRow[23] = row[25]; // library_rate
            bookRow[24] = row[26]; // translators
            bookRow[25] = row[27]; // city
            bookRow[26] = row[28]; // source_url
            bookBatch.add(bookRow);

            String bookId = (String) row[0];
            String authorIds = (String) row[19];
            if (authorIds != null && !authorIds.isBlank()) {
                for (String id : authorIds.split(",")) {
                    authorLinkBatch.add(new Object[]{bookId, id.trim()});
                }
            }
            String genreCodes = (String) row[20];
            if (genreCodes != null && !genreCodes.isBlank()) {
                for (String code : genreCodes.split(",")) {
                    genreLinkBatch.add(new Object[]{bookId, code.trim()});
                }
            }
        }

        jt.batchUpdate(insertBookSql, bookBatch, 1000, (ps, row) -> {
            int idx = 1;
            ps.setString(idx++, (String) row[0]);
            ps.setString(idx++, (String) row[1]);
            ps.setString(idx++, (String) row[2]);
            ps.setInt(idx++, (Integer) row[3]);
            ps.setString(idx++, (String) row[4]);
            ps.setString(idx++, (String) row[5]);
            ps.setString(idx++, (String) row[6]);
            ps.setString(idx++, (String) row[7]);
            ps.setLong(idx++, (Long) row[8]);
            ps.setString(idx++, (String) row[9]);
            ps.setString(idx++, (String) row[10]);
            ps.setInt(idx++, (Integer) row[11]);
            ps.setInt(idx++, (Integer) row[12]);
            ps.setString(idx++, (String) row[13]);
            ps.setString(idx++, (String) row[14]);
            ps.setInt(idx++, (Integer) row[15]);
            ps.setInt(idx++, (Integer) row[16]);
            ps.setString(idx++, (String) row[17]);
            ps.setString(idx++, (String) row[18]);
            ps.setString(idx++, (String) row[19]);
            if (row[20] == null) ps.setNull(idx++, java.sql.Types.INTEGER); else ps.setInt(idx++, (Integer) row[20]);
            ps.setString(idx++, (String) row[21]);
            ps.setString(idx++, (String) row[22]);
            ps.setInt(idx++, row[23] == null ? 0 : (Integer) row[23]);
            ps.setString(idx++, (String) row[24]);
            ps.setString(idx++, (String) row[25]);
            ps.setString(idx++, (String) row[26]);
        });

        // Re-import must replace metadata links instead of accumulating stale authors/genres.
        List<Object[]> bookIdsForCleanup = new ArrayList<>(booksData.size());
        for (Object[] row : booksData) bookIdsForCleanup.add(new Object[]{row[0]});
        jt.batchUpdate("DELETE FROM book_authors WHERE book_id = ?", bookIdsForCleanup, 1000,
                (ps, row) -> ps.setString(1, (String) row[0]));
        jt.batchUpdate("DELETE FROM book_genres WHERE book_id = ?", bookIdsForCleanup, 1000,
                (ps, row) -> ps.setString(1, (String) row[0]));

        if (!authorLinkBatch.isEmpty()) {
            // authorLinkBatch зараз містить книгу + ключ автора
            // Перетворимо ключі на реальні ID
            List<Object[]> realAuthorLinks = new ArrayList<>();
            for (Object[] link : authorLinkBatch) {
                String bookId = (String) link[0];
                String authorKey = (String) link[1];
                String realId = authorCache.get(authorKey);
                if (realId != null) {
                    realAuthorLinks.add(new Object[]{bookId, realId});
                } else {
                    log.warn("Author key not found in cache: {}", authorKey);
                }
            }
            if (!realAuthorLinks.isEmpty()) {
                jt.batchUpdate("INSERT OR IGNORE INTO book_authors (book_id, author_id) VALUES (?, ?)",
                        realAuthorLinks, 1000, (ps, row) -> {
                            ps.setString(1, (String) row[0]);
                            ps.setString(2, (String) row[1]);
                        });
            }
        }
        if (!genreLinkBatch.isEmpty()) {
            jt.batchUpdate("INSERT OR IGNORE INTO book_genres (book_id, genre_code) VALUES (?, ?)",
                    genreLinkBatch, 1000, (ps, row) -> {
                        ps.setString(1, (String) row[0]);
                        ps.setString(2, (String) row[1]);
                    });
        }

        log.debug("Batch inserted {} books", bookBatch.size());
    }

    /**
     * Батчева вставка авторів.
     */
    public void batchInsertAuthors(List<Author> authors) {
        if (authors == null || authors.isEmpty()) return;
        JdbcTemplate jt = getJdbcTemplate();
        String sql = """
                INSERT OR IGNORE INTO authors (id, first_name, middle_name, last_name, search_name)
                VALUES (?, ?, ?, ?, ?)
                """;
        List<Object[]> batch = new ArrayList<>();
        for (Author a : authors) {
            String searchName = buildSearchName(a);
            batch.add(new Object[]{
                    a.getId().asString(),
                    a.getFirstName(),
                    a.getMiddleName(),
                    a.getLastName(),
                    searchName
            });
        }
        jt.batchUpdate(sql, batch);
        log.debug("Batch inserted {} authors", authors.size());
    }

    /**
     * Батчева вставка жанрів.
     */
    public void batchInsertGenres(List<Genre> genres) {
        if (genres == null || genres.isEmpty()) return;
        JdbcTemplate jt = getJdbcTemplate();
        String sql = """
                INSERT OR IGNORE INTO genres (code, name, parent_code, fb2_code)
                VALUES (?, ?, ?, ?)
                """;
        List<Object[]> batch = new ArrayList<>();
        for (Genre g : genres) {
            batch.add(new Object[]{
                    g.getId().asString(),
                    g.getName(),
                    g.getParentId() != null ? g.getParentId().asString() : null,
                    g.getFb2Code()
            });
        }
        jt.batchUpdate(sql, batch);
        log.debug("Batch inserted {} genres", genres.size());
    }

    private String buildSearchName(Author author) {
        return (author.getLastName() != null ? author.getLastName() : "") + " " +
                (author.getFirstName() != null ? author.getFirstName() : "") + " " +
                (author.getMiddleName() != null ? author.getMiddleName() : "");
    }
}