package com.myhomelibcorp.infrastructure.backup;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myhomelibcorp.application.port.out.backup.UserDataTransferPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Stage 22 portable user-data transfer. The JSON format deliberately stores
 * book-scoped rows as flat arrays carrying LibID/sourceBookId identities so
 * export remains independent of catalogue metadata and restore can target a
 * freshly imported catalogue.
 */
@Component
@Slf4j
public class VersionedUserDataTransferAdapter implements UserDataTransferPort {

    private static final String FILTER_PREFIX = "filter.global.";
    private static final int ID_CACHE_LIMIT = 50_000;
    private static final int MAX_STRING_LENGTH = 10000;
    private static final int BATCH_SIZE = 1000;

    private static final Set<String> VALID_TABLE_NAMES = Set.of(
            "bookState", "readingProgress", "readingHistory", "readingStats",
            "bookmarks", "groups", "groupMemberships", "savedSearches"
    );

    private final CollectionManager collectionManager;
    private final ApplicationSettingsPort settings;
    private final ObjectMapper mapper;
    private final JsonFactory jsonFactory = new JsonFactory();
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();

    private final Path readerPreferencesFile = AppPaths.configDir().resolve("reader-preferences.json");
    private final Path readerBookPreferencesFile = AppPaths.configDir().resolve("reader-book-preferences.json");

    public VersionedUserDataTransferAdapter(CollectionManager collectionManager,
                                            ApplicationSettingsPort settings,
                                            ObjectMapper mapper) {
        this.collectionManager = collectionManager;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override
    public ExportResult exportTo(Path targetFile) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile");
        validatePath(targetFile);

        Files.createDirectories(targetFile.toAbsolutePath().getParent());
        Path tmp = targetFile.resolveSibling(targetFile.getFileName() + ".tmp");
        AtomicLong bookRecords = new AtomicLong();
        AtomicLong groupMemberships = new AtomicLong();
        AtomicLong bookmarks = new AtomicLong();
        AtomicLong history = new AtomicLong();
        AtomicLong savedSearches = new AtomicLong();
        AtomicLong readerOverrides = new AtomicLong();

        try (JsonGenerator g = jsonFactory.createGenerator(tmp.toFile(), com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            g.useDefaultPrettyPrinter();
            g.writeStartObject();
            g.writeNumberField("schemaVersion", CURRENT_SCHEMA_VERSION);
            g.writeStringField("format", "myhomelib-user-data");
            g.writeStringField("exportedAt", Instant.now().toString());

            exportBookState(g, bookRecords);
            exportReadingProgress(g, null);
            exportReadingHistory(g, history);
            exportReadingStats(g, null);
            exportBookmarks(g, bookmarks);
            exportGroups(g, null);
            exportGroupMemberships(g, groupMemberships);
            exportSavedSearches(g, savedSearches);

            // ⚡ ВИПРАВЛЕНО: ручна серіалізація filterSettings замість writeObjectField
            g.writeFieldName("filterSettings");
            g.writeStartObject();
            Map<String, String> filterSettings = settings.findByPrefix(FILTER_PREFIX);
            for (Map.Entry<String, String> entry : filterSettings.entrySet()) {
                g.writeStringField(entry.getKey(), entry.getValue());
            }
            g.writeEndObject();

            g.writeObjectFieldStart("readerSettings");
            exportReaderSettings(g, readerOverrides);
            g.writeEndObject();

            g.writeEndObject();
        } catch (UncheckedIOException e) {
            Files.deleteIfExists(tmp);
            throw e.getCause();
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw new IOException("Cannot export portable user data", e);
        }

        moveAtomic(tmp, targetFile);
        return new ExportResult(CURRENT_SCHEMA_VERSION, bookRecords.get(), groupMemberships.get(), bookmarks.get(),
                history.get(), savedSearches.get(), readerOverrides.get());
    }

    @Override
    public ImportResult restoreFrom(Path sourceFile) throws IOException {
        Objects.requireNonNull(sourceFile, "sourceFile");
        validatePath(sourceFile);

        ObjectNode root = parseAndValidateSource(sourceFile);

        int sourceVersion = sourceSchemaVersion(root);
        if (sourceVersion < 1 || sourceVersion > CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported user-data schemaVersion=" + sourceVersion +
                    ", current=" + CURRENT_SCHEMA_VERSION);
        }

        ObjectNode migrated = migrateSequentially(root.deepCopy(), sourceVersion);

        RestoreCounters counters = new RestoreCounters(sourceVersion);
        BookIdCache idCache = new BookIdCache(ID_CACHE_LIMIT);
        Map<String, JsonNode> restoredReaderOverrides = new LinkedHashMap<>();
        Set<String> statsCleared = new HashSet<>();

        var dataSource = collectionManager.getCurrentDataSource();
        if (dataSource == null) {
            throw new IOException("No active collection data source");
        }

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        try {
            tx.execute(status -> {
                try {
                    restoreBookState(array(migrated, "bookState"), idCache, counters);
                    restoreReadingProgress(array(migrated, "readingProgress"), idCache, counters);
                    restoreReadingHistory(array(migrated, "readingHistory"), idCache, counters);
                    restoreReadingStats(array(migrated, "readingStats"), idCache, counters, statsCleared);
                    restoreBookmarks(array(migrated, "bookmarks"), idCache, counters);
                    restoreGroups(array(migrated, "groups"), counters);
                    restoreGroupMemberships(array(migrated, "groupMemberships"), idCache, counters);
                    restoreSavedSearches(array(migrated, "savedSearches"), counters);
                    collectReaderOverrides(migrated.path("readerSettings").path("perBook"), idCache, counters, restoredReaderOverrides);
                } catch (DataAccessException e) {
                    throw new RuntimeException("Database error during restore", e);
                } catch (Exception e) {
                    throw new RuntimeException("Error during restore", e);
                }
                return null;
            });
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else if (cause instanceof DataAccessException) {
                throw new IOException("Database error during restore", cause);
            } else {
                throw new IOException("Cannot restore portable user data", e);
            }
        }

        try {
            applyFilterSettings(migrated.path("filterSettings"));
            applyReaderSettings(migrated.path("readerSettings"), restoredReaderOverrides);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to apply reader settings", e);
        }

        return counters.result();
    }

    // ============= Export Methods =============

    private void exportBookState(JsonGenerator g, AtomicLong counter) throws IOException {
        writeRows(g, "bookState",
                "SELECT b.lib_id AS libId, b.id AS sourceBookId, " +
                        "COALESCE(b.rate,0) AS rate, COALESCE(b.progress,0) AS progress, " +
                        "COALESCE(b.review,'') AS review " +
                        "FROM books b " +
                        "WHERE COALESCE(b.rate,0) <> 0 OR COALESCE(b.progress,0) <> 0 " +
                        "OR TRIM(COALESCE(b.review,'')) <> '' " +
                        "ORDER BY b.id",
                counter);
    }

    private void exportReadingProgress(JsonGenerator g, AtomicLong counter) throws IOException {
        writeRows(g, "readingProgress",
                "SELECT b.lib_id AS libId, b.id AS sourceBookId, " +
                        "rp.paragraph_id AS paragraphId, rp.char_offset AS charOffset, " +
                        "rp.percent AS percent, rp.updated_at AS updatedAt, " +
                        "rp.anchor_id AS anchorId, COALESCE(rp.paragraph_index,0) AS paragraphIndex " +
                        "FROM reading_progress rp JOIN books b ON b.id=rp.book_id " +
                        "ORDER BY rp.updated_at, b.id",
                counter);
    }

    private void exportReadingHistory(JsonGenerator g, AtomicLong counter) throws IOException {
        writeRows(g, "readingHistory",
                "SELECT b.lib_id AS libId, b.id AS sourceBookId, " +
                        "rh.last_opened_at AS lastOpenedAt, rh.open_count AS openCount " +
                        "FROM reading_history rh JOIN books b ON b.id=rh.book_id " +
                        "ORDER BY rh.last_opened_at, b.id",
                counter);
    }

    private void exportReadingStats(JsonGenerator g, AtomicLong counter) throws IOException {
        writeRows(g, "readingStats",
                "SELECT b.lib_id AS libId, b.id AS sourceBookId, " +
                        "rs.first_read_at AS firstReadAt, rs.last_read_at AS lastReadAt, " +
                        "COALESCE(rs.total_reading_seconds,0) AS totalReadingSeconds, " +
                        "COALESCE(rs.reading_sessions,0) AS readingSessions, " +
                        "COALESCE(rs.start_percent,0) AS startPercent, " +
                        "COALESCE(rs.end_percent,0) AS endPercent, " +
                        "COALESCE(rs.current_percent,0) AS currentPercent, " +
                        "rs.completed_at AS completedAt " +
                        "FROM reading_stats rs JOIN books b ON b.id=rs.book_id " +
                        "ORDER BY rs.id",
                counter);
    }

    private void exportBookmarks(JsonGenerator g, AtomicLong counter) throws IOException {
        writeRows(g, "bookmarks",
                "SELECT bm.id AS id, b.lib_id AS libId, b.id AS sourceBookId, " +
                        "bm.paragraph_id AS paragraphId, COALESCE(bm.char_offset,0) AS charOffset, " +
                        "COALESCE(bm.position,0) AS position, bm.chapter_title AS chapterTitle, " +
                        "bm.context AS context, bm.created_at AS createdAt " +
                        "FROM bookmarks bm JOIN books b ON b.id=bm.book_id " +
                        "ORDER BY bm.created_at, bm.id",
                counter);
    }

    private void exportGroups(JsonGenerator g, AtomicLong counter) throws IOException {
        writeRows(g, "groups",
                "SELECT name, COALESCE(allow_delete,1) AS allowDelete " +
                        "FROM groups ORDER BY id",
                counter);
    }

    private void exportGroupMemberships(JsonGenerator g, AtomicLong counter) throws IOException {
        writeRows(g, "groupMemberships",
                "SELECT g.name AS groupName, b.lib_id AS libId, b.id AS sourceBookId " +
                        "FROM book_groups bg " +
                        "JOIN groups g ON g.id=bg.group_id " +
                        "JOIN books b ON b.id=bg.book_id " +
                        "ORDER BY g.name, b.id",
                counter);
    }

    private void exportSavedSearches(JsonGenerator g, AtomicLong counter) throws IOException {
        writeRows(g, "savedSearches",
                "SELECT id, name, query, filters, created_at AS createdAt, " +
                        "last_used AS lastUsed, COALESCE(use_count,0) AS useCount " +
                        "FROM saved_searches ORDER BY name",
                counter);
    }

    private void exportReaderSettings(JsonGenerator g, AtomicLong counter) throws IOException {
        fileLock.readLock().lock();
        try {
            JsonNode global = readJson(readerPreferencesFile);
            if (global == null) {
                g.writeNullField("global");
            } else {
                g.writeObjectField("global", global);
            }

            g.writeArrayFieldStart("perBook");
            Map<String, JsonNode> perBook = readReaderBookPreferences();
            for (Map.Entry<String, JsonNode> entry : perBook.entrySet()) {
                Identity identity = identityForBookId(entry.getKey());
                if (identity == null) continue;
                g.writeStartObject();
                if (identity.libId() != null) {
                    g.writeStringField("libId", identity.libId());
                } else {
                    g.writeNullField("libId");
                }
                g.writeStringField("sourceBookId", identity.sourceBookId());
                g.writeObjectField("preferences", entry.getValue());
                g.writeEndObject();
                counter.incrementAndGet();
            }
            g.writeEndArray();
        } finally {
            fileLock.readLock().unlock();
        }
    }

    private void writeRows(JsonGenerator g, String field, String sql, AtomicLong counter) throws IOException {
        if (!VALID_TABLE_NAMES.contains(field)) {
            throw new IllegalArgumentException("Invalid table name: " + field);
        }

        g.writeArrayFieldStart(field);
        try {
            jdbc().query(sql, (ResultSet rs) -> {
                try {
                    while (rs.next()) {
                        g.writeStartObject();
                        var md = rs.getMetaData();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            String name = md.getColumnLabel(i);
                            Object value = rs.getObject(i);
                            if (value == null) {
                                g.writeNullField(name);
                            } else if (value instanceof Number || value instanceof Boolean) {
                                g.writeObjectField(name, value);
                            } else {
                                String strValue = value.toString();
                                g.writeStringField(name, truncateString(strValue));
                            }
                        }
                        g.writeEndObject();
                        if (counter != null) {
                            counter.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        g.writeEndArray();
    }

    // ============= Restore Methods =============

    private void restoreBookState(ArrayNode rows, BookIdCache cache, RestoreCounters c) {
        for (JsonNode row : rows) {
            withBook(row, cache, c, bookId -> {
                try {
                    int updated = jdbc().update(
                            "UPDATE books SET rate=?, progress=?, review=? WHERE id=?",
                            safeInt(row, "rate", 0),
                            safeInt(row, "progress", 0),
                            safeNullableText(row, "review"),
                            bookId);
                    if (updated > 0) {
                        c.matchedBooks++;
                    }
                } catch (DataAccessException e) {
                    log.warn("Failed to restore book state for book {}: {}", bookId, e.getMessage());
                }
            });
        }
    }

    private void restoreReadingProgress(ArrayNode rows, BookIdCache cache, RestoreCounters c) {
        for (JsonNode row : rows) {
            withBook(row, cache, c, bookId -> {
                try {
                    jdbc().update("""
                            INSERT INTO reading_progress(book_id,paragraph_id,char_offset,percent,updated_at,anchor_id,paragraph_index)
                            VALUES(?,?,?,?,?,?,?)
                            ON CONFLICT(book_id) DO UPDATE SET 
                                paragraph_id=excluded.paragraph_id,
                                char_offset=excluded.char_offset,
                                percent=excluded.percent,
                                updated_at=excluded.updated_at,
                                anchor_id=excluded.anchor_id,
                                paragraph_index=excluded.paragraph_index
                            """,
                            bookId,
                            safeText(row, "paragraphId", ""),
                            safeInt(row, "charOffset", 0),
                            safeDouble(row, "percent", 0.0),
                            safeText(row, "updatedAt", Instant.now().toString()),
                            safeNullableText(row, "anchorId"),
                            safeInt(row, "paragraphIndex", 0));
                } catch (DataAccessException e) {
                    log.warn("Failed to restore reading progress for book {}: {}", bookId, e.getMessage());
                }
            });
        }
    }

    private void restoreReadingHistory(ArrayNode rows, BookIdCache cache, RestoreCounters c) {
        for (JsonNode row : rows) {
            withBook(row, cache, c, bookId -> {
                try {
                    jdbc().update("""
                            INSERT INTO reading_history(book_id,last_opened_at,open_count) 
                            VALUES(?,?,?)
                            ON CONFLICT(book_id) DO UPDATE SET 
                                last_opened_at=excluded.last_opened_at,
                                open_count=excluded.open_count
                            """,
                            bookId,
                            safeText(row, "lastOpenedAt", Instant.now().toString()),
                            Math.max(1, safeInt(row, "openCount", 1)));
                    c.historyEntries++;
                } catch (DataAccessException e) {
                    log.warn("Failed to restore reading history for book {}: {}", bookId, e.getMessage());
                }
            });
        }
    }

    private void restoreReadingStats(ArrayNode rows, BookIdCache cache, RestoreCounters c, Set<String> cleared) {
        for (JsonNode row : rows) {
            withBook(row, cache, c, bookId -> {
                try {
                    if (cleared.add(bookId)) {
                        jdbc().update("DELETE FROM reading_stats WHERE book_id=?", bookId);
                    }
                    jdbc().update("""
                            INSERT INTO reading_stats(
                                book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,
                                start_percent,end_percent,current_percent,completed_at)
                            VALUES(?,?,?,?,?,?,?,?,?)
                            """,
                            bookId,
                            safeText(row, "firstReadAt", Instant.now().toString()),
                            safeText(row, "lastReadAt", Instant.now().toString()),
                            safeLong(row, "totalReadingSeconds", 0L),
                            safeInt(row, "readingSessions", 0),
                            safeInt(row, "startPercent", 0),
                            safeInt(row, "endPercent", 0),
                            safeInt(row, "currentPercent", 0),
                            safeNullableText(row, "completedAt"));
                } catch (DataAccessException e) {
                    log.warn("Failed to restore reading stats for book {}: {}", bookId, e.getMessage());
                }
            });
        }
    }

    private void restoreBookmarks(ArrayNode rows, BookIdCache cache, RestoreCounters c) {
        for (JsonNode row : rows) {
            withBook(row, cache, c, bookId -> {
                try {
                    String id = safeText(row, "id", UUID.randomUUID().toString());
                    jdbc().update("""
                            INSERT INTO bookmarks(
                                id,book_id,paragraph_id,char_offset,position,chapter_title,context,created_at)
                            VALUES(?,?,?,?,?,?,?,?)
                            ON CONFLICT(id) DO UPDATE SET 
                                book_id=excluded.book_id,
                                paragraph_id=excluded.paragraph_id,
                                char_offset=excluded.char_offset,
                                position=excluded.position,
                                chapter_title=excluded.chapter_title,
                                context=excluded.context,
                                created_at=excluded.created_at
                            """,
                            id,
                            bookId,
                            safeText(row, "paragraphId", ""),
                            safeInt(row, "charOffset", 0),
                            safeDouble(row, "position", 0.0),
                            safeNullableText(row, "chapterTitle"),
                            safeNullableText(row, "context"),
                            safeText(row, "createdAt", Instant.now().toString()));
                    c.bookmarks++;
                } catch (DataAccessException e) {
                    log.warn("Failed to restore bookmark for book {}: {}", bookId, e.getMessage());
                }
            });
        }
    }

    private void restoreGroups(ArrayNode rows, RestoreCounters c) {
        for (JsonNode row : rows) {
            String name = safeText(row, "name", "").trim();
            if (name.isEmpty()) continue;

            try {
                Integer id = jdbc().query("SELECT id FROM groups WHERE name=?",
                        rs -> rs.next() ? rs.getInt(1) : null, name);

                if (id == null) {
                    jdbc().update("INSERT INTO groups(name,allow_delete) VALUES(?,?)",
                            name, safeInt(row, "allowDelete", 1));
                } else {
                    jdbc().update("UPDATE groups SET allow_delete=? WHERE id=?",
                            safeInt(row, "allowDelete", 1), id);
                }
                c.groups++;
            } catch (DataAccessException e) {
                log.warn("Failed to restore group {}: {}", name, e.getMessage());
            }
        }
    }

    private void restoreGroupMemberships(ArrayNode rows, BookIdCache cache, RestoreCounters c) {
        for (JsonNode row : rows) {
            withBook(row, cache, c, bookId -> {
                try {
                    String groupName = safeText(row, "groupName", "").trim();
                    if (groupName.isEmpty()) return;

                    Integer groupId = jdbc().query("SELECT id FROM groups WHERE name=?",
                            rs -> rs.next() ? rs.getInt(1) : null, groupName);

                    if (groupId == null) {
                        jdbc().update("INSERT INTO groups(name,allow_delete) VALUES(?,1)", groupName);
                        groupId = jdbc().queryForObject("SELECT id FROM groups WHERE name=?",
                                Integer.class, groupName);
                    }

                    jdbc().update("INSERT OR IGNORE INTO book_groups(book_id,group_id) VALUES(?,?)",
                            bookId, groupId);
                    c.groupMemberships++;
                } catch (DataAccessException e) {
                    log.warn("Failed to restore group membership for book {}: {}", bookId, e.getMessage());
                }
            });
        }
    }

    private void restoreSavedSearches(ArrayNode rows, RestoreCounters c) {
        for (JsonNode row : rows) {
            String name = safeText(row, "name", "").trim();
            if (name.isEmpty()) continue;

            try {
                String existing = jdbc().query("SELECT id FROM saved_searches WHERE name=?",
                        rs -> rs.next() ? rs.getString(1) : null, name);

                if (existing == null) {
                    jdbc().update("""
                            INSERT INTO saved_searches(id,name,query,filters,created_at,last_used,use_count) 
                            VALUES(?,?,?,?,?,?,?)
                            """,
                            safeText(row, "id", UUID.randomUUID().toString()),
                            name,
                            safeText(row, "query", ""),
                            safeNullableText(row, "filters"),
                            safeText(row, "createdAt", Instant.now().toString()),
                            safeText(row, "lastUsed", Instant.now().toString()),
                            safeInt(row, "useCount", 0));
                } else {
                    jdbc().update("""
                            UPDATE saved_searches 
                            SET query=?,filters=?,created_at=?,last_used=?,use_count=? 
                            WHERE id=?
                            """,
                            safeText(row, "query", ""),
                            safeNullableText(row, "filters"),
                            safeText(row, "createdAt", Instant.now().toString()),
                            safeText(row, "lastUsed", Instant.now().toString()),
                            safeInt(row, "useCount", 0),
                            existing);
                }
                c.savedSearches++;
            } catch (DataAccessException e) {
                log.warn("Failed to restore saved search {}: {}", name, e.getMessage());
            }
        }
    }

    private void collectReaderOverrides(JsonNode rows, BookIdCache cache, RestoreCounters c,
                                        Map<String, JsonNode> target) {
        if (!rows.isArray()) return;

        for (JsonNode row : rows) {
            withBook(row, cache, c, bookId -> {
                JsonNode prefs = row.get("preferences");
                if (prefs != null && prefs.isObject()) {
                    target.put(bookId, prefs);
                    c.readerOverrides++;
                }
            });
        }
    }

    // ============= Helper Methods =============

    private void withBook(JsonNode row, BookIdCache cache, RestoreCounters c, Consumer<String> action) {
        String key = identityKey(row);
        Optional<String> resolved = cache.get(key);

        if (resolved == null) {
            resolved = Optional.ofNullable(resolveBookId(row));
            cache.put(key, resolved);
        }

        if (resolved.isPresent()) {
            action.accept(resolved.get());
        } else {
            c.unmatchedBooks++;
        }
    }

    private String resolveBookId(JsonNode row) {
        String libId = safeNullableText(row, "libId");
        if (libId != null && !libId.isBlank()) {
            try {
                List<String> ids = jdbc().query(
                        "SELECT id FROM books WHERE lib_id=? ORDER BY id LIMIT 1",
                        (rs, n) -> rs.getString(1),
                        libId);
                if (!ids.isEmpty()) {
                    return ids.get(0);
                }
            } catch (DataAccessException e) {
                log.debug("Failed to resolve book by libId {}: {}", libId, e.getMessage());
            }
        }

        String source = safeNullableText(row, "sourceBookId");
        if (source != null && !source.isBlank()) {
            try {
                List<String> ids = jdbc().query(
                        "SELECT id FROM books WHERE id=? LIMIT 1",
                        (rs, n) -> rs.getString(1),
                        source);
                if (!ids.isEmpty()) {
                    return ids.get(0);
                }
            } catch (DataAccessException e) {
                log.debug("Failed to resolve book by id {}: {}", source, e.getMessage());
            }
        }
        return null;
    }

    private Identity identityForBookId(String id) {
        try {
            List<Identity> rows = jdbc().query(
                    "SELECT lib_id,id FROM books WHERE id=? LIMIT 1",
                    (rs, n) -> new Identity(rs.getString(1), rs.getString(2)),
                    id);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException e) {
            log.debug("Failed to get identity for book id {}: {}", id, e.getMessage());
            return null;
        }
    }

    private String identityKey(JsonNode row) {
        String lib = safeNullableText(row, "libId");
        if (lib != null && !lib.isBlank()) {
            return "L:" + lib;
        }
        return "I:" + safeText(row, "sourceBookId", "");
    }

    private void applyFilterSettings(JsonNode node) {
        if (!node.isObject()) return;
        node.fields().forEachRemaining(e -> {
            if (e.getKey().startsWith(FILTER_PREFIX)) {
                try {
                    settings.put(e.getKey(), e.getValue().asText(""));
                } catch (Exception ex) {
                    log.warn("Failed to apply filter setting {}: {}", e.getKey(), ex.getMessage());
                }
            }
        });
    }

    private void applyReaderSettings(JsonNode readerSettings, Map<String, JsonNode> restoredOverrides) throws IOException {
        if (readerSettings.has("global") && !readerSettings.get("global").isNull()) {
            writeJsonAtomic(readerPreferencesFile, readerSettings.get("global"));
        }

        if (!restoredOverrides.isEmpty()) {
            mergeReaderBookPreferences(restoredOverrides);
        }
    }

    private ObjectNode parseAndValidateSource(Path sourceFile) throws IOException {
        JsonNode parsed;
        try {
            parsed = mapper.readTree(sourceFile.toFile());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot read portable user data", e);
        }

        if (!(parsed instanceof ObjectNode object)) {
            throw new IOException("Portable user-data root must be a JSON object");
        }
        return object;
    }

    private int sourceSchemaVersion(ObjectNode root) {
        if (root.has("schemaVersion")) {
            return root.path("schemaVersion").asInt(0);
        }
        return root.path("version").asInt(1);
    }

    private ObjectNode migrateSequentially(ObjectNode root, int version) throws IOException {
        int current = version;
        while (current < CURRENT_SCHEMA_VERSION) {
            if (current == 1) {
                migrateV1ToV2(root);
                current = 2;
            } else {
                throw new IOException("No user-data migration from schema " + current);
            }
        }
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        return root;
    }

    private void migrateV1ToV2(ObjectNode root) {
        if (!root.has("bookState") && root.has("ratings")) {
            root.set("bookState", root.remove("ratings"));
        }
        if (!root.has("readingProgress") && root.has("reading")) {
            root.set("readingProgress", root.remove("reading"));
        }

        for (String name : List.of("bookState", "readingProgress", "readingHistory",
                "readingStats", "bookmarks", "groups",
                "groupMemberships", "savedSearches")) {
            if (!root.has(name) || !root.get(name).isArray()) {
                root.set(name, mapper.createArrayNode());
            }
        }

        if (!root.has("filterSettings") || !root.get("filterSettings").isObject()) {
            root.set("filterSettings", mapper.createObjectNode());
        }

        if (!root.has("readerSettings") || !root.get("readerSettings").isObject()) {
            ObjectNode reader = mapper.createObjectNode();
            reader.putNull("global");
            reader.set("perBook", mapper.createArrayNode());
            root.set("readerSettings", reader);
        } else if (!root.path("readerSettings").has("perBook")) {
            ((ObjectNode) root.path("readerSettings")).set("perBook", mapper.createArrayNode());
        }
    }

    private Map<String, JsonNode> readReaderBookPreferences() {
        JsonNode root = readJson(readerBookPreferencesFile);
        if (root == null || !root.isObject()) {
            return Map.of();
        }

        Map<String, JsonNode> result = new LinkedHashMap<>();
        root.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private void mergeReaderBookPreferences(Map<String, JsonNode> restored) throws IOException {
        fileLock.writeLock().lock();
        try {
            ObjectNode root = mapper.createObjectNode();
            JsonNode existing = readJson(readerBookPreferencesFile);
            if (existing != null && existing.isObject()) {
                existing.fields().forEachRemaining(e -> root.set(e.getKey(), e.getValue()));
            }
            restored.forEach(root::set);
            writeJsonAtomic(readerBookPreferencesFile, root);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private JsonNode readJson(Path file) {
        fileLock.readLock().lock();
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return mapper.readTree(file.toFile());
        } catch (Exception e) {
            log.warn("Cannot read {}: {}", file, e.getMessage());
            return null;
        } finally {
            fileLock.readLock().unlock();
        }
    }

    private void writeJsonAtomic(Path file, JsonNode value) throws IOException {
        fileLock.writeLock().lock();
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            moveAtomic(tmp, file);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void validatePath(Path path) throws IOException {
        if (path.isAbsolute()) {
            Path normalized = path.normalize();
            if (normalized.toString().contains("..")) {
                throw new IOException("Invalid path contains parent directory references");
            }
        }
    }

    private JdbcTemplate jdbc() {
        if (!collectionManager.hasActiveCollection()) {
            throw new IllegalStateException("No active collection");
        }
        return collectionManager.getCurrentJdbcTemplate();
    }

    private ArrayNode array(ObjectNode root, String name) {
        JsonNode n = root.path(name);
        return n instanceof ArrayNode a ? a : mapper.createArrayNode();
    }

    // ============= Safe Data Access Methods =============

    private static String safeText(JsonNode n, String field, String fallback) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return fallback;
        String text = v.asText();
        return text == null ? fallback : truncateString(text);
    }

    private static String safeNullableText(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String text = v.asText();
        if (text == null || text.isBlank()) return null;
        return truncateString(text);
    }

    private static int safeInt(JsonNode n, String field, int defaultValue) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isNumber()) return defaultValue;
        try {
            return v.asInt(defaultValue);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long safeLong(JsonNode n, String field, long defaultValue) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isNumber()) return defaultValue;
        try {
            return v.asLong(defaultValue);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double safeDouble(JsonNode n, String field, double defaultValue) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isNumber()) return defaultValue;
        try {
            return v.asDouble(defaultValue);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String truncateString(String value) {
        if (value == null) return null;
        if (value.length() <= MAX_STRING_LENGTH) return value;
        return value.substring(0, MAX_STRING_LENGTH);
    }

    // ============= Inner Classes =============

    private static class BookIdCache {
        private final int maxSize;
        private final Map<String, Optional<String>> cache;

        BookIdCache(int maxSize) {
            this.maxSize = maxSize;
            this.cache = new LinkedHashMap<String, Optional<String>>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Optional<String>> eldest) {
                    return size() > maxSize;
                }
            };
        }

        synchronized Optional<String> get(String key) {
            return cache.get(key);
        }

        synchronized void put(String key, Optional<String> value) {
            cache.put(key, value);
        }

        synchronized void clear() {
            cache.clear();
        }
    }

    private record Identity(String libId, String sourceBookId) {}

    private static final class RestoreCounters {
        final int sourceVersion;
        long matchedBooks;
        long unmatchedBooks;
        long groups;
        long groupMemberships;
        long bookmarks;
        long historyEntries;
        long savedSearches;
        long readerOverrides;

        RestoreCounters(int sourceVersion) {
            this.sourceVersion = sourceVersion;
        }

        ImportResult result() {
            return new ImportResult(sourceVersion, CURRENT_SCHEMA_VERSION, matchedBooks,
                    unmatchedBooks, groups, groupMemberships, bookmarks,
                    historyEntries, savedSearches, readerOverrides);
        }
    }
}