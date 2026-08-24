package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.cache.DictionaryCache;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private Map<String, String> authorCache;
    private Map<String, String> genreCache;

    private JdbcTemplate getJdbcTemplate() { return collectionManager.getCurrentJdbcTemplate(); }

    public long importFile(Path file, int batchSize, Path rootDirectory) {
        return importFile(file, batchSize, rootDirectory, null);
    }

    public long importFile(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag) {
        int effectiveBatch = Math.max(50, Math.min(batchSize <= 0 ? 1000 : batchSize, 10_000));
        Path root = rootDirectory != null ? rootDirectory.toAbsolutePath().normalize()
                : (file.getParent() != null ? file.getParent().toAbsolutePath().normalize() : Path.of(".").toAbsolutePath().normalize());
        log.info("Starting INPX import: {} (root: {}, batch: {})", file, root, effectiveBatch);

        this.authorCache = buildAuthorCache();
        this.genreCache = buildGenreCache();

        boolean optimized = false;
        long imported;
        try {
            bulkOptimizer.enableBulkInsertMode();
            optimized = true;

            var dataSource = collectionManager.getCurrentDataSource();
            if (dataSource != null) {
                TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                Long result = transaction.execute(status -> {
                    ImportOutcome outcome = importTransactional(file, effectiveBatch, root, cancelFlag);
                    if (outcome.cancelled()) {
                        status.setRollbackOnly();
                        log.info("INPX import cancelled; transaction rolled back after {} parsed books", outcome.count());
                        return 0L;
                    }
                    return outcome.count();
                });
                imported = result == null ? 0L : result;
            } else {
                // Unit tests and detached tooling can run without an active collection datasource.
                ImportOutcome outcome = importTransactional(file, effectiveBatch, root, cancelFlag);
                imported = outcome.cancelled() ? 0L : outcome.count();
            }
        } finally {
            if (optimized) bulkOptimizer.disableBulkInsertMode();
        }

        dictionaryCache.loadAuthors(authorRepository.findAll());
        dictionaryCache.loadGenres(genreRepository.findAll());
        log.info("INPX import completed: {} books", imported);
        return imported;
    }

    /**
     * Executes the catalog mutation as one database transaction when a datasource is active.
     * Any exception (or explicit cancellation) therefore restores the pre-import catalog,
     * including relation links and indexes.
     */
    private ImportOutcome importTransactional(Path file, int effectiveBatch, Path root, AtomicBoolean cancelFlag) {
        String sourceMarker = buildSourceMarker(file, root);
        Map<String, Author> pendingAuthors = new LinkedHashMap<>();
        Map<String, Genre> pendingGenres = new LinkedHashMap<>();
        Map<String, Boolean> localCache = new HashMap<>();
        AtomicLong total = new AtomicLong();
        List<Object[]> books = new ArrayList<>(effectiveBatch);
        boolean indexesDropped = false;
        boolean cancelled = false;
        try {
            dropIndexes();
            indexesDropped = true;
            markPriorCatalogRowsDeleted(sourceMarker);

            Iterator<InpxRecord> iterator = reader.read(file);
            while (iterator.hasNext()) {
                if (cancelFlag != null && cancelFlag.get()) {
                    cancelled = true;
                    break;
                }
                Object[] row = normalize(iterator.next(), pendingAuthors, pendingGenres, root, localCache, sourceMarker);
                if (row == null) continue;
                books.add(row);
                if (books.size() >= effectiveBatch) {
                    flush(books, pendingAuthors, pendingGenres);
                    total.addAndGet(books.size());
                    books.clear();
                }
            }
            if (!cancelled && !books.isEmpty()) {
                flush(books, pendingAuthors, pendingGenres);
                total.addAndGet(books.size());
                books.clear();
            }
            return new ImportOutcome(total.get(), cancelled);
        } finally {
            if (indexesDropped) createIndexes();
        }
    }

    private record ImportOutcome(long count, boolean cancelled) {}

    public long importFile(Path file, int batchSize) { return importFile(file, batchSize, null, null); }

    @Async("taskExecutor")
    public void refreshCachesAsync() {
        try {
            dictionaryCache.loadAuthors(authorRepository.findAll());
            dictionaryCache.loadGenres(genreRepository.findAll());
        } catch (Exception e) {
            log.error("Failed to refresh dictionary caches after INPX import", e);
        }
    }

    private void flush(List<Object[]> books, Map<String, Author> pendingAuthors, Map<String, Genre> pendingGenres) {
        flushPendingEntities(pendingAuthors, pendingGenres);
        batchWriter.batchInsertFull(books, authorCache, genreCache);
    }

    private void flushPendingEntities(Map<String, Author> pendingAuthors, Map<String, Genre> pendingGenres) {
        if (!pendingAuthors.isEmpty()) {
            List<Author> list = new ArrayList<>(pendingAuthors.values());
            batchWriter.batchInsertAuthors(list);
            for (Author a : list) authorCache.put(buildAuthorKey(a), a.getId().asString());
            pendingAuthors.clear();
        }
        if (!pendingGenres.isEmpty()) {
            List<Genre> list = new ArrayList<>(pendingGenres.values());
            batchWriter.batchInsertGenres(list);
            for (Genre g : list) genreCache.put(g.getId().asString(), g.getId().asString());
            pendingGenres.clear();
        }
    }

    private Map<String, String> buildAuthorCache() {
        Map<String, String> cache = new HashMap<>();
        for (Author a : authorRepository.findAll()) cache.put(buildAuthorKey(a), a.getId().asString());
        return cache;
    }

    private Map<String, String> buildGenreCache() {
        Map<String, String> cache = new HashMap<>();
        for (Genre g : genreRepository.findAll()) cache.put(g.getId().asString(), g.getId().asString());
        return cache;
    }

    private String buildAuthorKey(Author a) {
        return safe(a.getFirstName()) + "|" + safe(a.getMiddleName()) + "|" + safe(a.getLastName());
    }

    private Object[] normalize(InpxRecord raw,
                               Map<String, Author> pendingAuthors,
                               Map<String, Genre> pendingGenres,
                               Path root,
                               Map<String, Boolean> localCache,
                               String sourceMarker) {
        try {
            List<String> authorKeys = parseAuthors(raw.field("AUTHOR"), pendingAuthors);
            if (authorKeys.isEmpty()) authorKeys = ensureUnknownAuthor(pendingAuthors);
            List<String> genreCodes = parseGenres(raw.field("GENRE"), pendingGenres);

            String title = defaultIfBlank(raw.field("TITLE"), "Без назви");
            String series = raw.field("SERIES");
            int seq = parseInt(raw.field("SERNO"), 0);
            long size = parseLong(raw.field("SIZE"), 0L);
            String ext = normalizeExt(raw.field("EXT"));
            String fileName = defaultIfBlank(raw.field("FILE"), "unknown");
            if (!ext.isBlank() && !fileName.toLowerCase(Locale.ROOT).endsWith("." + ext.toLowerCase(Locale.ROOT))) {
                fileName += "." + ext;
            }

            String archiveName = normalizeRelative(raw.archiveName());
            String explicitFolder = normalizeRelative(raw.field("FOLDER"));
            String folder;
            String archiveEntry;
            boolean local;
            if (!archiveName.isBlank()) {
                folder = archiveName;
                archiveEntry = fileName;
                Path archivePath = root.resolve(archiveName).normalize();
                local = archivePath.startsWith(root) && localCache.computeIfAbsent(archiveName, k -> Files.isRegularFile(archivePath));
            } else {
                folder = explicitFolder;
                archiveEntry = "";
                Path loosePath = explicitFolder.isBlank() ? root.resolve(fileName) : root.resolve(explicitFolder).resolve(fileName);
                loosePath = loosePath.normalize();
                local = loosePath.startsWith(root) && Files.isRegularFile(loosePath);
            }

            String language = defaultIfBlank(raw.field("LANG"), "uk");
            try { language = LanguageCode.of(language).value(); } catch (Exception ignored) { language = "uk"; }
            String keywords = raw.field("KEYWORDS");
            String annotation = raw.field("ANNOTATION");
            boolean deleted = parseDeleted(raw.field("DEL"));
            String libId = raw.field("LIBID");
            String identity = !libId.isBlank()
                    ? "inpx:libid:" + libId
                    : sourceMarker + ":" + archiveName + ":" + fileName;
            String bookId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
            String now = LocalDateTime.now().format(DATE_FORMATTER);

            int year = parseYear(firstNonBlank(raw.field("YEAR"), raw.field("PUBYEAR"), raw.field("DATE")));
            String publisher = firstNonBlank(raw.field("PUBLISHER"), raw.field("PUBLISH"));
            int libraryRate = parseInt(firstNonBlank(raw.field("LIBRATE"), raw.field("LIBRARYRATE")), 0);
            String translators = firstNonBlank(raw.field("TRANSLATORS"), raw.field("TRANSLATOR"));
            String city = raw.field("CITY");

            // Row contract is intentionally explicit: JdbcBatchWriter consumes these indices.
            Object[] row = new Object[29];
            int i = 0;
            row[i++] = bookId;                         // 0
            row[i++] = title;                          // 1
            row[i++] = series;                         // 2
            row[i++] = seq;                            // 3
            row[i++] = fileName;                       // 4
            row[i++] = folder;                         // 5
            row[i++] = archiveEntry;                   // 6
            row[i++] = language;                       // 7
            row[i++] = size;                           // 8
            row[i++] = keywords;                       // 9
            row[i++] = annotation;                     // 10
            row[i++] = 0;                              // 11 user rate (preserved)
            row[i++] = 0;                              // 12 progress (preserved)
            row[i++] = now;                            // 13
            row[i++] = firstNonBlank(raw.field("ISBN")); // 14
            row[i++] = deleted ? 1 : 0;               // 15
            row[i++] = local ? 1 : 0;                 // 16
            row[i++] = "";                            // 17 review (preserved)
            row[i++] = now;                            // 18 created_at (preserved)
            row[i++] = String.join(",", authorKeys);   // 19 author keys
            row[i++] = String.join(",", genreCodes);   // 20 genres
            row[i++] = root.toString();                // 21 collection_root
            row[i++] = year > 0 ? year : null;        // 22
            row[i++] = publisher;                      // 23
            row[i++] = libId;                          // 24 stable catalog identity
            row[i++] = libraryRate;                    // 25 library/catalog rate
            row[i++] = translators;                    // 26
            row[i++] = city;                           // 27
            row[i] = sourceMarker;                    // 28 catalog source marker; survives update/reappearance
            return row;
        } catch (Exception e) {
            log.warn("Skipping malformed INPX record {} from {}", raw.field("FILE"), raw.inpName(), e);
            return null;
        }
    }

    private List<String> parseAuthors(String value, Map<String, Author> pending) {
        List<String> keys = new ArrayList<>(2);
        if (value == null || value.isBlank() || value.equals(":")) return keys;
        for (String item : value.split(":")) {
            String[] p = item.trim().split(",", -1);
            String last = p.length > 0 ? p[0].trim() : "";
            String first = p.length > 1 ? p[1].trim() : "";
            String middle = p.length > 2 ? p[2].trim() : "";
            if (last.isBlank() && first.isBlank() && middle.isBlank()) continue;
            String key = first + "|" + middle + "|" + last;
            if (!authorCache.containsKey(key) && !pending.containsKey(key)) pending.put(key, new Author(first, middle, last));
            keys.add(key);
        }
        return keys;
    }

    private List<String> ensureUnknownAuthor(Map<String, Author> pending) {
        String key = "||Невідомий Автор";
        if (!authorCache.containsKey(key) && !pending.containsKey(key)) pending.put(key, new Author("", "", "Невідомий Автор"));
        return List.of(key);
    }

    private List<String> parseGenres(String value, Map<String, Genre> pending) {
        List<String> result = new ArrayList<>(2);
        if (value == null || value.isBlank()) return result;
        for (String item : value.split(":")) {
            String code = item.trim();
            if (code.isBlank()) continue;
            if (!genreCache.containsKey(code) && !pending.containsKey(code)) pending.put(code, new Genre(code, code));
            result.add(code);
        }
        return result;
    }

    private static String normalizeExt(String s) {
        String v = safe(s).trim();
        while (v.startsWith(".")) v = v.substring(1);
        return v;
    }
    private static String normalizeRelative(String s) {
        String v = safe(s).replace('\\', '/').trim();
        while (v.startsWith("/")) v = v.substring(1);
        if (v.contains("../") || v.equals("..")) return "";
        return v;
    }
    private static boolean parseDeleted(String s) {
        String v = safe(s).trim().toLowerCase(Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("deleted");
    }
    private static int parseYear(String s) {
        String v = safe(s).trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<!\\d)(18|19|20|21)\\d{2}(?!\\d)").matcher(v);
        if (m.find()) return parseInt(m.group(), 0);
        return 0;
    }
    private String buildSourceMarker(Path sourceInpx, Path root) {
        Path source = sourceInpx.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        String stablePath;
        try {
            stablePath = source.startsWith(normalizedRoot)
                    ? normalizeRelative(normalizedRoot.relativize(source).toString())
                    : source.toString().replace('\\', '/');
        } catch (Exception ignored) {
            stablePath = source.getFileName() == null ? source.toString() : source.getFileName().toString();
        }
        return "inpx:" + stablePath;
    }

    /**
     * Missing-record policy: keep the row and all user-owned relations/state, but mark it
     * deleted/non-local. Reappearing records use the same deterministic id and are
     * reactivated by the normal UPSERT in the same transaction.
     */
    private void markPriorCatalogRowsDeleted(String sourceMarker) {
        if (!collectionManager.hasActiveCollection()) return;
        getJdbcTemplate().update(
                "UPDATE books SET deleted = 1, local = 0 WHERE source_url = ?",
                sourceMarker);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return "";
    }
    private static int parseInt(String s, int fallback) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; } }
    private static long parseLong(String s, long fallback) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return fallback; } }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String defaultIfBlank(String s, String fallback) { return s == null || s.isBlank() ? fallback : s.trim(); }

    private void dropIndexes() {
        if (!collectionManager.hasActiveCollection()) return;
        try {
            getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_books_title");
            getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_books_series");
            getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_authors_last_name");
            getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_book_authors_book_author");
            getJdbcTemplate().execute("DROP INDEX IF EXISTS idx_book_genres_book_genre");
        } catch (Exception e) { log.warn("Error dropping indexes", e); }
    }

    private void createIndexes() {
        if (!collectionManager.hasActiveCollection()) return;
        try {
            getJdbcTemplate().execute("CREATE INDEX IF NOT EXISTS idx_books_title ON books(title)");
            getJdbcTemplate().execute("CREATE INDEX IF NOT EXISTS idx_books_series ON books(series)");
            getJdbcTemplate().execute("CREATE INDEX IF NOT EXISTS idx_authors_last_name ON authors(last_name)");
            getJdbcTemplate().execute("CREATE INDEX IF NOT EXISTS idx_book_authors_book_author ON book_authors(book_id, author_id)");
            getJdbcTemplate().execute("CREATE INDEX IF NOT EXISTS idx_book_genres_book_genre ON book_genres(book_id, genre_code)");
        } catch (Exception e) { log.warn("Error creating indexes", e); }
    }
}
