package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookDenormalizedValues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;

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
     * ОПТИМІЗОВАНО: зменшено кількість запитів до БД
     */
    public void batchInsertFull(List<Object[]> booksData,
                                Map<String, String> authorCache,
                                Map<String, String> genreCache) {
        if (booksData.isEmpty()) return;

        JdbcTemplate jt = getJdbcTemplate();
        long startTime = System.currentTimeMillis();

        String insertBookSql = """
            INSERT INTO books (
                id, title, series, sequence_number, file_name, folder,
                archive_entry, language, file_size, keywords, annotation,
                rate, progress, update_date, isbn, deleted, local,
                review, created_at, collection_root, year, publisher,
                lib_id, library_rate, translators, city, source_url, format, author_sort
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                source_url = CASE WHEN COALESCE(excluded.source_url,'') <> '' THEN excluded.source_url ELSE books.source_url END,
                format = CASE WHEN books.local = 1 THEN books.format ELSE excluded.format END,
                author_sort = excluded.author_sort
            """;

        // ОПТИМІЗОВАНО: збільшено розмір батчу
        List<Object[]> bookBatch = new ArrayList<>(booksData.size());
        List<Object[]> authorLinkBatch = new ArrayList<>(booksData.size() * 2);
        List<Object[]> genreLinkBatch = new ArrayList<>(booksData.size() * 2);

        for (Object[] row : booksData) {
            Object[] bookRow = new Object[29];
            System.arraycopy(row, 0, bookRow, 0, 19);
            bookRow[19] = row[21]; // collection_root
            bookRow[20] = row[22]; // year
            bookRow[21] = row[23]; // publisher
            bookRow[22] = row[24]; // lib_id
            bookRow[23] = row[25]; // library_rate
            bookRow[24] = row[26]; // translators
            bookRow[25] = row[27]; // city
            bookRow[26] = row[28]; // source_url
            bookRow[27] = BookDenormalizedValues.format((String) row[4]);
            bookRow[28] = authorSortFromKeys((String) row[19]);
            bookBatch.add(bookRow);

            String bookId = (String) row[0];
            String authorIds = (String) row[19];
            if (authorIds != null && !authorIds.isBlank()) {
                String[] ids = authorIds.split(",");
                for (String id : ids) {
                    String trimmed = id.trim();
                    if (!trimmed.isEmpty()) {
                        authorLinkBatch.add(new Object[]{bookId, trimmed});
                    }
                }
            }
            String genreCodes = (String) row[20];
            if (genreCodes != null && !genreCodes.isBlank()) {
                String[] codes = genreCodes.split(",");
                for (String code : codes) {
                    String trimmed = code.trim();
                    if (!trimmed.isEmpty()) {
                        genreLinkBatch.add(new Object[]{bookId, trimmed});
                    }
                }
            }
        }

        // ОПТИМІЗОВАНО: використання batchUpdate з великим розміром батчу
        jt.batchUpdate(insertBookSql, bookBatch);

        // ОПТИМІЗОВАНО: видалення старих зв'язків одним запитом
        if (!bookBatch.isEmpty()) {
            List<String> bookIds = new ArrayList<>();
            for (Object[] row : bookBatch) {
                bookIds.add((String) row[0]);
            }
            String placeholders = String.join(",", bookIds.stream().map(id -> "?").toArray(String[]::new));
            jt.update("DELETE FROM book_authors WHERE book_id IN (" + placeholders + ")", bookIds.toArray());
            jt.update("DELETE FROM book_genres WHERE book_id IN (" + placeholders + ")", bookIds.toArray());
        }

        // ОПТИМІЗОВАНО: вставка зв'язків з авторами
        if (!authorLinkBatch.isEmpty()) {
            List<Object[]> realAuthorLinks = new ArrayList<>(authorLinkBatch.size());
            java.util.LinkedHashSet<String> missingAuthorKeys = new java.util.LinkedHashSet<>();
            for (Object[] link : authorLinkBatch) {
                String bookId = (String) link[0];
                String authorKey = (String) link[1];
                String realId = authorCache.get(authorKey);
                if (realId != null) {
                    realAuthorLinks.add(new Object[]{bookId, realId});
                } else {
                    missingAuthorKeys.add(authorKey);
                }
            }
            if (!missingAuthorKeys.isEmpty()) {
                log.warn("{} author key(s) were not resolved in current batch; first keys: {}",
                        missingAuthorKeys.size(),
                        missingAuthorKeys.stream().limit(5).toList());
            }
            if (!realAuthorLinks.isEmpty()) {
                jt.batchUpdate("INSERT OR IGNORE INTO book_authors (book_id, author_id) VALUES (?, ?)",
                        realAuthorLinks);
            }
        }

        // ОПТИМІЗОВАНО: вставка зв'язків з жанрами
        if (!genreLinkBatch.isEmpty()) {
            jt.batchUpdate("INSERT OR IGNORE INTO book_genres (book_id, genre_code) VALUES (?, ?)",
                    genreLinkBatch);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.debug("Batch inserted {} books in {} ms", bookBatch.size(), duration);
    }

    /** Derives the same normalized author order as BookDenormalizedValues without materializing Author objects. */
    static String authorSortFromKeys(String encodedKeys) {
        if (encodedKeys == null || encodedKeys.isBlank()) return "";
        String best = null;
        for (String key : encodedKeys.split(",")) {
            String[] parts = key.split("\\|", -1);
            String first = parts.length > 0 ? parts[0].trim() : "";
            String middle = parts.length > 1 ? parts[1].trim() : "";
            String last = parts.length > 2 ? parts[2].trim() : "";
            String value = (last + " " + first + " " + middle).trim()
                    .toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
            if (!value.isBlank() && (best == null || value.compareTo(best) < 0)) best = value;
        }
        return best == null ? "" : best;
    }

    /**
     * Батчева вставка авторів.
     * ОПТИМІЗОВАНО: збільшено розмір батчу та використання prepared statement
     */
    public void batchInsertAuthors(List<Author> authors) {
        if (authors == null || authors.isEmpty()) return;
        JdbcTemplate jt = getJdbcTemplate();
        String sql = """
                INSERT OR IGNORE INTO authors (id, first_name, middle_name, last_name, search_name)
                VALUES (?, ?, ?, ?, ?)
                """;
        List<Object[]> batch = new ArrayList<>(authors.size());
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
     * Inserts missing authors and resolves the actual persistent IDs in bounded SQL chunks.
     * ОПТИМІЗОВАНО: збільшено розмір чанку та використання IN-запитів
     */
    public Map<String, String> batchInsertAuthorsAndResolveIds(List<Author> authors) {
        if (authors == null || authors.isEmpty()) return Map.of();

        batchInsertAuthors(authors);

        Map<AuthorPair, List<String>> fullKeysByPair = new LinkedHashMap<>();
        for (Author author : authors) {
            fullKeysByPair.computeIfAbsent(authorPairKey(author), ignored -> new ArrayList<>())
                    .add(authorKey(author));
        }

        List<AuthorPair> pairs = new ArrayList<>(fullKeysByPair.keySet());
        Map<String, String> resolved = new HashMap<>();
        final int pairsPerQuery = 500;

        for (int from = 0; from < pairs.size(); from += pairsPerQuery) {
            int to = Math.min(pairs.size(), from + pairsPerQuery);
            StringBuilder sql = new StringBuilder(
                    "SELECT id, first_name, last_name FROM authors WHERE ");
            List<Object> args = new ArrayList<>((to - from) * 2);
            for (int i = from; i < to; i++) {
                if (i > from) sql.append(" OR ");
                // Do not wrap indexed columns in COALESCE here.  idx_authors_unique_name
                // is defined on (first_name, last_name); applying a function to the
                // columns forces SQLite into table scans and makes large INPX imports
                // progressively slower as the authors table grows.
                sql.append("(first_name = ? AND last_name = ?)");
                AuthorPair pair = pairs.get(i);
                args.add(pair.firstName());
                args.add(pair.lastName());
            }

            List<Object[]> rows = getJdbcTemplate().query(
                    sql.toString(),
                    (rs, rowNum) -> new Object[]{
                            rs.getString("id"),
                            rs.getString("first_name"),
                            rs.getString("last_name")
                    },
                    args.toArray());

            for (Object[] row : rows) {
                String id = (String) row[0];
                AuthorPair pair = new AuthorPair(safe((String) row[1]), safe((String) row[2]));
                for (String fullKey : fullKeysByPair.getOrDefault(pair, List.of())) {
                    resolved.put(fullKey, id);
                }
            }
        }

        if (resolved.size() < authors.size()) {
            log.warn("Resolved only {} author keys for {} pending author objects", resolved.size(), authors.size());
        }
        return resolved;
    }

    private static String authorKey(Author a) {
        return safe(a.getFirstName()) + "|" + safe(a.getMiddleName()) + "|" + safe(a.getLastName());
    }

    private static AuthorPair authorPairKey(Author a) {
        // Keep the pair structured. Author names can legally contain '|', so serializing
        // first/last and splitting at the first delimiter loses information.
        return new AuthorPair(safe(a.getFirstName()), safe(a.getLastName()));
    }

    private record AuthorPair(String firstName, String lastName) { }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Батчева вставка жанрів.
     * ОПТИМІЗОВАНО: збільшено розмір батчу
     */
    public void batchInsertGenres(List<Genre> genres) {
        if (genres == null || genres.isEmpty()) return;
        JdbcTemplate jt = getJdbcTemplate();
        String sql = """
                INSERT OR IGNORE INTO genres (code, name, parent_code, fb2_code)
                VALUES (?, ?, ?, ?)
                """;
        List<Object[]> batch = new ArrayList<>(genres.size());
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