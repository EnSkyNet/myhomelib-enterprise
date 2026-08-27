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

@Slf4j
@Component
public class VersionedUserDataTransferAdapter implements UserDataTransferPort {

    private static final String FILTER_PREFIX = "filter.global.";
    private static final int ID_CACHE_LIMIT = 50_000;
    private static final int MAX_STRING_LENGTH = 10000;

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

            // ✅ ВИПРАВЛЕНО: серіалізація через ObjectMapper
            g.writeFieldName("filterSettings");
            Map<String, String> filterSettings = settings.findByPrefix(FILTER_PREFIX);
            g.writeRawValue(mapper.writeValueAsString(filterSettings));

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
        ObjectNode root = (ObjectNode) mapper.readTree(sourceFile.toFile());
        int sourceVersion = root.path("schemaVersion").asInt(1);
        // ... решта методу без змін
        return new ImportResult(sourceVersion, CURRENT_SCHEMA_VERSION, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    // ... всі інші методи без змін (exportBookState, exportReadingProgress, etc.)
    // ... і метод writeRows

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
            // ... решта методу
            g.writeEndArray();
        } finally {
            fileLock.readLock().unlock();
        }
    }

    private void writeRows(JsonGenerator g, String field, String sql, AtomicLong counter) throws IOException {
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
                                g.writeStringField(name, value.toString());
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

    private void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
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

    private JdbcTemplate jdbc() {
        if (!collectionManager.hasActiveCollection()) {
            throw new IllegalStateException("No active collection");
        }
        return collectionManager.getCurrentJdbcTemplate();
    }

    private static String safeText(JsonNode n, String field, String fallback) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return fallback;
        return v.asText();
    }

    private static String safeNullableText(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }

    private static int safeInt(JsonNode n, String field, int defaultValue) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isNumber()) return defaultValue;
        return v.asInt(defaultValue);
    }

    private static long safeLong(JsonNode n, String field, long defaultValue) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isNumber()) return defaultValue;
        return v.asLong(defaultValue);
    }

    private static double safeDouble(JsonNode n, String field, double defaultValue) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isNumber()) return defaultValue;
        return v.asDouble(defaultValue);
    }
}