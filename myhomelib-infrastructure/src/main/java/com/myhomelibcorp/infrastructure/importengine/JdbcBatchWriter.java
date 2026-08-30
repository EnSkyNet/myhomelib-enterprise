package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookDenormalizedValues;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.AuthorSearchNameNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Collection;

@Component
@RequiredArgsConstructor
@Slf4j
public class JdbcBatchWriter {

    private final CollectionManager collectionManager;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    /**
     * Вставляє батч книг з колонкою collection_root.
     * ОПТИМІЗОВАНО: зменшено кількість запитів до БД
     */
    public void batchInsertFull(List<Object[]> booksData,
                                Map<String, String> authorCache) {
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
            bookRow[28] = (String) row[29];
            bookBatch.add(bookRow);

            String bookId = (String) row[0];
            appendLinks(authorLinkBatch, bookId, row[19]);
            appendLinks(genreLinkBatch, bookId, row[20]);
        }

        // ОПТИМІЗОВАНО: використання batchUpdate з великим розміром батчу
        jt.batchUpdate(insertBookSql, bookBatch);

        // SQLite builds commonly expose a much smaller bind-variable limit than our 10k import batch.
        // Delete relationship rows in bounded chunks instead of producing one huge IN (?, ...).
        if (!bookBatch.isEmpty()) {
            List<String> bookIds = new ArrayList<>(bookBatch.size());
            for (Object[] row : bookBatch) bookIds.add((String) row[0]);
            deleteLinksByBookIds(jt, "book_authors", bookIds);
            deleteLinksByBookIds(jt, "book_genres", bookIds);
        }

        // ОПТИМІЗОВАНО: вставка зв'язків з авторами
        if (!authorLinkBatch.isEmpty()) {
            List<Object[]> realAuthorLinks = new ArrayList<>(authorLinkBatch.size());
            java.util.LinkedHashSet<String> missingAuthorKeys = new java.util.LinkedHashSet<>();
            for (Object[] link : authorLinkBatch) {
                String bookId = (String) link[0];
                String authorKey = (String) link[1];
                String realId = authorCache.getOrDefault(authorKey, authorKey);
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

    private static final int SQLITE_LINK_DELETE_CHUNK = 400;

    private static void appendLinks(List<Object[]> target, String bookId, Object raw) {
        if (raw == null) return;
        if (raw instanceof Collection<?> values) {
            for (Object value : values) appendLink(target, bookId, value == null ? "" : value.toString());
            return;
        }
        // Backward compatibility for older tests/import adapters that still pass CSV strings.
        String text = raw.toString();
        if (text.isBlank()) return;
        for (String value : text.split(",")) appendLink(target, bookId, value);
    }

    private static void appendLink(List<Object[]> target, String bookId, String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty()) target.add(new Object[]{bookId, normalized});
    }

    private static void deleteLinksByBookIds(JdbcTemplate jt, String table, List<String> bookIds) {
        // Table is an internal constant controlled by this class, never user input.
        for (int from = 0; from < bookIds.size(); from += SQLITE_LINK_DELETE_CHUNK) {
            int to = Math.min(bookIds.size(), from + SQLITE_LINK_DELETE_CHUNK);
            List<String> chunk = bookIds.subList(from, to);
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            jt.update("DELETE FROM " + table + " WHERE book_id IN (" + placeholders + ")", chunk.toArray());
        }
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
     * Resolves candidate author IDs to persistent IDs using an exact indexed triple lookup.
     * The returned map is candidate-id -> persistent-id; author names are never serialized.
     */
    public Map<String, String> batchInsertAuthorsAndResolveIds(List<Author> authors) {
        if (authors == null || authors.isEmpty()) return Map.of();

        Map<AuthorName, List<Author>> byName = new LinkedHashMap<>();
        for (Author author : authors) {
            byName.computeIfAbsent(AuthorName.of(author), ignored -> new ArrayList<>()).add(author);
        }

        Map<AuthorName, String> existing = findExistingAuthorIds(new ArrayList<>(byName.keySet()));
        Map<String, String> resolved = new HashMap<>();
        List<Author> missing = new ArrayList<>();
        for (Map.Entry<AuthorName, List<Author>> entry : byName.entrySet()) {
            String id = existing.get(entry.getKey());
            if (id == null) {
                Author first = entry.getValue().get(0);
                missing.add(first);
                id = first.getId().asString();
            }
            for (Author candidate : entry.getValue()) {
                resolved.put(candidate.getId().asString(), id);
            }
        }

        batchInsertAuthors(missing);
        return resolved;
    }

    private Map<AuthorName, String> findExistingAuthorIds(List<AuthorName> names) {
        Map<AuthorName, String> result = new HashMap<>();
        final int namesPerQuery = 250; // 3 bind variables each; safely below SQLite limits.
        for (int from = 0; from < names.size(); from += namesPerQuery) {
            int to = Math.min(names.size(), from + namesPerQuery);
            StringBuilder sql = new StringBuilder(
                    "SELECT id, first_name, middle_name, last_name FROM authors WHERE ");
            List<Object> args = new ArrayList<>((to - from) * 3);
            for (int i = from; i < to; i++) {
                if (i > from) sql.append(" OR ");
                sql.append("(first_name = ? AND middle_name = ? AND last_name = ?)");
                AuthorName name = names.get(i);
                args.add(name.firstName());
                args.add(name.middleName());
                args.add(name.lastName());
            }
            getJdbcTemplate().query(sql.toString(), rs -> {
                AuthorName name = new AuthorName(
                        safe(rs.getString("first_name")),
                        safe(rs.getString("middle_name")),
                        safe(rs.getString("last_name")));
                result.putIfAbsent(name, rs.getString("id"));
            }, args.toArray());
        }
        return result;
    }

    private record AuthorName(String firstName, String middleName, String lastName) {
        static AuthorName of(Author author) {
            return new AuthorName(safe(author.getFirstName()), safe(author.getMiddleName()), safe(author.getLastName()));
        }
    }

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
        return AuthorSearchNameNormalizer.normalize(
                author.getFirstName(), author.getMiddleName(), author.getLastName());
    }
}