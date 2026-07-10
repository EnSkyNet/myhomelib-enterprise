package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.cache.DictionaryCache;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class InpxImportPipeline {

    private final InpxReader reader;
    private final JdbcBatchWriter batchWriter;
    private final BulkImportOptimizer bulkOptimizer;
    private final CollectionManager collectionManager;
    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final DictionaryCache dictionaryCache;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    public long importFile(Path file, int batchSize, Path rootDirectory) {
        log.info("Starting INPX import: {} (root: {})", file, rootDirectory);

        Map<String, String> authorCache = buildAuthorCache();
        Map<String, String> genreCache = buildGenreCache();

        bulkOptimizer.enableBulkInsertMode();
        dropIndexes();

        Iterator<Object[]> iterator = reader.read(file);
        List<Object[]> batch = new ArrayList<>(batchSize);
        AtomicLong total = new AtomicLong(0);

        String collectionRoot = rootDirectory != null ? rootDirectory.toString() : (file.getParent() != null ? file.getParent().toString() : "");
        String baseFolder = rootDirectory != null ? rootDirectory.toString() : (file.getParent() != null ? file.getParent().toString() : "");

        while (iterator.hasNext()) {
            Object[] raw = iterator.next();
            Object[] normalized = normalize(raw, authorCache, genreCache, collectionRoot, baseFolder);
            if (normalized != null) {
                batch.add(normalized);
                if (batch.size() >= batchSize) {
                    batchWriter.batchInsertFull(batch, authorCache, genreCache);
                    total.addAndGet(batch.size());
                    batch.clear();
                    log.debug("Imported {} books so far", total.get());
                }
            }
        }
        if (!batch.isEmpty()) {
            batchWriter.batchInsertFull(batch, authorCache, genreCache);
            total.addAndGet(batch.size());
        }

        createIndexes();
        bulkOptimizer.disableBulkInsertMode();

        dictionaryCache.loadAuthors(authorRepository.findAll());
        dictionaryCache.loadGenres(genreRepository.findAll());

        log.info("INPX import completed: {} books", total.get());
        return total.get();
    }

    public long importFile(Path file, int batchSize) {
        return importFile(file, batchSize, null);
    }

    private Map<String, String> buildAuthorCache() {
        Map<String, String> cache = new HashMap<>();
        List<Author> authors = authorRepository.findAll();
        for (Author a : authors) {
            String key = (a.getFirstName() != null ? a.getFirstName() : "") + "|" +
                    (a.getMiddleName() != null ? a.getMiddleName() : "") + "|" +
                    (a.getLastName() != null ? a.getLastName() : "");
            cache.put(key, a.getId().asString());
        }
        log.info("Built author cache with {} entries", cache.size());
        return cache;
    }

    private Map<String, String> buildGenreCache() {
        Map<String, String> cache = new HashMap<>();
        List<Genre> genres = genreRepository.findAll();
        for (Genre g : genres) {
            cache.put(g.getId().asString(), g.getId().asString());
        }
        log.info("Built genre cache with {} entries", cache.size());
        return cache;
    }

    private Object[] normalize(Object[] raw, Map<String, String> authorCache, Map<String, String> genreCache, String collectionRoot, String baseFolder) {
        try {
            String[] parts = (String[]) raw;
            if (parts.length < 8) return null;

            // ---- Автори ----
            String authorsStr = parts[0].trim();
            List<String> authorIds = new ArrayList<>(2);
            if (!authorsStr.isEmpty() && !authorsStr.equals(":")) {
                for (String entry : authorsStr.split(":")) {
                    entry = entry.trim();
                    if (entry.isEmpty()) continue;
                    String[] nameParts = entry.split(",");
                    String lastName = nameParts.length > 0 ? nameParts[0].trim() : "";
                    String firstName = nameParts.length > 1 ? nameParts[1].trim() : "";
                    String middleName = nameParts.length > 2 ? nameParts[2].trim() : "";
                    if (lastName.isEmpty() && firstName.isEmpty() && middleName.isEmpty()) continue;
                    String key = firstName + "|" + middleName + "|" + lastName;
                    String id = authorCache.get(key);
                    if (id == null) {
                        Author newAuthor = new Author(firstName, middleName, lastName);
                        Author saved = authorRepository.save(newAuthor);
                        id = saved.getId().asString();
                        authorCache.put(key, id);
                    }
                    authorIds.add(id);
                }
            }
            if (authorIds.isEmpty()) {
                String key = "||Неведомий Автор";
                String id = authorCache.get(key);
                if (id == null) {
                    Author newAuthor = new Author("", "", "Неведомий Автор");
                    Author saved = authorRepository.save(newAuthor);
                    id = saved.getId().asString();
                    authorCache.put(key, id);
                }
                authorIds.add(id);
            }

            // ---- Жанри ----
            String genresStr = parts[1].trim();
            List<String> genreCodes = new ArrayList<>(2);
            if (!genresStr.isEmpty()) {
                for (String code : genresStr.split(":")) {
                    String clean = code.trim();
                    if (!clean.isEmpty() && !genreCache.containsKey(clean)) {
                        Genre newGenre = new Genre(clean, clean);
                        genreRepository.save(newGenre);
                        genreCache.put(clean, clean);
                    }
                    genreCodes.add(clean);
                }
            }

            // ---- Поля книги ----
            String title = parts[2].trim();
            if (title.isEmpty()) title = "Без назви";
            String series = parts[3].trim();
            int seqNumber = 0;
            if (parts.length > 4 && !parts[4].trim().isEmpty()) {
                try { seqNumber = Integer.parseInt(parts[4].trim()); } catch (NumberFormatException ignored) {}
            }
            String fileName = parts.length > 5 ? parts[5].trim() : "unknown.fb2";
            if (fileName.isEmpty()) fileName = "unknown.fb2";
            long fileSize = parts.length > 6 && !parts[6].trim().isEmpty() ?
                    Long.parseLong(parts[6].trim()) : 0;
            String language = parts.length > 8 && !parts[8].trim().isEmpty() ?
                    parts[8].trim() : "uk";
            try { LanguageCode.of(language); } catch (Exception e) { language = "uk"; }
            String keywords = parts.length > 12 ? parts[12].trim() : "";
            String annotation = parts.length > 13 ? parts[13].trim() : "";

            // ---- Формуємо масив ----
            String bookId = BookId.generate().asString();
            String now = LocalDateTime.now().format(DATE_FORMATTER);
            Object[] row = new Object[22];
            int idx = 0;
            row[idx++] = bookId;
            row[idx++] = title;
            row[idx++] = series;
            row[idx++] = seqNumber;
            row[idx++] = fileName;                      // file_name
            row[idx++] = baseFolder;                    // folder (базова папка)
            row[idx++] = "";                            // archive_entry
            row[idx++] = language;
            row[idx++] = fileSize;
            row[idx++] = keywords;
            row[idx++] = annotation;
            row[idx++] = 0; // rate
            row[idx++] = 0; // progress
            row[idx++] = now; // update_date
            row[idx++] = null; // isbn
            row[idx++] = 0; // deleted
            row[idx++] = 1; // local
            row[idx++] = ""; // review
            row[idx++] = now; // created_at
            row[idx++] = String.join(",", authorIds);
            row[idx++] = String.join(",", genreCodes);
            row[idx++] = collectionRoot;                // collection_root
            return row;

        } catch (Exception e) {
            log.warn("Error normalizing row", e);
            return null;
        }
    }

    private void dropIndexes() {
        try {
            getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_books_title");
            getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_books_series");
            getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_authors_last_name");
            getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_book_authors_book_author");
            getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_book_genres_book_genre");
            log.info("Indexes dropped");
        } catch (Exception e) {
            log.warn("Error dropping indexes", e);
        }
    }

    private void createIndexes() {
        try {
            getJdbcTemplate().execute("CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)");
            getJdbcTemplate().execute("CREATE INDEX IF NOT EXISTS idx_books_series ON books(series)");
            getJdbcTemplate().execute("CREATE INDEX IF NOT EXISTS idx_authors_last_name ON authors(last_name)");
            getJdbcTemplate().execute("CREATE INDEX IF NOT EXISTS idx_book_authors_book_author ON book_authors(book_id, author_id)");
            getJdbcTemplate().execute("CREATE INDEX IF NOT EXISTS idx_book_genres_book_genre ON book_genres(book_id, genre_code)");
            log.info("Indexes created");
        } catch (Exception e) {
            log.warn("Error creating indexes", e);
        }
    }
}