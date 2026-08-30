package com.myhomelibcorp.infrastructure.backup;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.backup.UserDataTransferPort;
import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
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
    private static final int MAX_READER_PREFERENCES_JSON_BYTES = 1024 * 1024;

    private final CollectionManager collectionManager;
    private final ApplicationSettingsPort settings;
    private final ObjectMapper mapper;
    private final JsonFactory jsonFactory = new JsonFactory();
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();

    private final Path readerPreferencesFile = AppPaths.configDir().resolve("reader-preferences.json");

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

        AtomicFileSupport.moveReplacing(tmp, targetFile);
        return new ExportResult(CURRENT_SCHEMA_VERSION, bookRecords.get(), groupMemberships.get(), bookmarks.get(),
                history.get(), savedSearches.get(), readerOverrides.get());
    }

    @Override
    public ImportResult restoreFrom(Path sourceFile) throws IOException {
        Objects.requireNonNull(sourceFile, "sourceFile");
        ManifestHeader header = inspectManifest(sourceFile);
        if (header.sourceVersion() == CURRENT_SCHEMA_VERSION) {
            return restoreCurrentSchemaStreaming(sourceFile, header);
        }
        return restoreLegacyManifest(sourceFile, header.sourceVersion());
    }

    private ImportResult restoreLegacyManifest(Path sourceFile, int detectedVersion) throws IOException {
        if (detectedVersion != 1) {
            throw new IOException("No user-data migration from schema v" + detectedVersion);
        }
        RestoreCounters counters = new RestoreCounters();
        BoundedIdentityCache identities = new BoundedIdentityCache();
        ReaderSettingsRestoreState readerSettings = new ReaderSettingsRestoreState();
        runDatabaseRestore(() -> {
            try {
                restoreLegacyV1DatabaseStreaming(sourceFile, identities, counters, readerSettings);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        restoreNonDatabaseSectionsStreaming(sourceFile);
        restoreReaderGlobal(readerSettings.global());
        return importResult(1, CURRENT_SCHEMA_VERSION, counters);
    }

    /**
     * Streams the pre-schema-v2 manifest without materializing large ratings/reading arrays.
     * v1 ratings map to v2 bookState and v1 reading maps to v2 readingProgress; optional
     * sections that already existed are restored directly. Unknown sections stay forward-safe.
     */
    private void restoreLegacyV1DatabaseStreaming(Path sourceFile, BoundedIdentityCache ids,
                                                  RestoreCounters counters,
                                                  ReaderSettingsRestoreState readerSettings) throws IOException {
        Set<String> seen = new HashSet<>();
        try (JsonParser parser = mapper.getFactory().createParser(sourceFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Portable user-data manifest must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken token = parser.nextToken();
                Consumer<JsonNode> consumer = switch (field) {
                    case "ratings" -> row -> restoreBookStateRow(row, ids, counters);
                    case "reading" -> row -> restoreReadingProgressRow(row, ids, counters);
                    case "readingHistory" -> row -> restoreReadingHistoryRow(row, ids, counters);
                    case "readingStats" -> row -> restoreReadingStatsRow(row, ids, counters);
                    case "bookmarks" -> row -> restoreBookmarkRow(row, ids, counters);
                    case "groups" -> row -> restoreGroupRow(row, counters);
                    case "groupMemberships" -> row -> restoreGroupMembershipRow(row, ids, counters);
                    case "savedSearches" -> row -> restoreSavedSearchRow(row, counters);
                    default -> null;
                };
                if (consumer != null) {
                    if (!seen.add(field)) throw new IOException("Duplicate portable user-data section: " + field);
                    if (token != JsonToken.START_ARRAY) {
                        throw new IOException("Portable user-data section is not an array: " + field);
                    }
                    streamArrayRows(parser, field, consumer);
                } else if ("readerSettings".equals(field)) {
                    if (!seen.add(field)) throw new IOException("Duplicate portable user-data section: " + field);
                    if (token != JsonToken.START_OBJECT) {
                        throw new IOException("Portable user-data section is not an object: " + field);
                    }
                    restoreReaderSettingsDatabaseStreaming(parser, ids, counters, readerSettings);
                } else {
                    parser.skipChildren();
                }
            }
        }
    }

    private ManifestHeader inspectManifest(Path sourceFile) throws IOException {
        int version = 1;
        boolean schemaVersionSeen = false;
        String format = null;
        try (JsonParser parser = mapper.getFactory().createParser(sourceFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Portable user-data manifest must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("schemaVersion".equals(field) && valueToken != null && valueToken.isNumeric()) {
                    version = parser.getIntValue();
                    schemaVersionSeen = true;
                } else if (!schemaVersionSeen && "version".equals(field) && valueToken != null && valueToken.isNumeric()) {
                    version = parser.getIntValue();
                } else if ("format".equals(field) && valueToken == JsonToken.VALUE_STRING) {
                    format = parser.getValueAsString();
                }
                parser.skipChildren();
            }
        }
        if (version < 1 || version > CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported portable user-data schema version: " + version);
        }
        if (version == CURRENT_SCHEMA_VERSION && format != null && !"myhomelib-user-data".equals(format)) {
            throw new IOException("Unsupported portable user-data format: " + format);
        }
        return new ManifestHeader(version, format);
    }

    private ImportResult restoreCurrentSchemaStreaming(Path sourceFile, ManifestHeader header) throws IOException {
        RestoreCounters counters = new RestoreCounters();
        BoundedIdentityCache identities = new BoundedIdentityCache();
        ReaderSettingsRestoreState readerSettings = new ReaderSettingsRestoreState();
        runDatabaseRestore(() -> {
            try {
                restoreDatabaseSectionsStreaming(sourceFile, identities, counters, readerSettings);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        restoreNonDatabaseSectionsStreaming(sourceFile);
        restoreReaderGlobal(readerSettings.global());
        return importResult(header.sourceVersion(), CURRENT_SCHEMA_VERSION, counters);
    }

    private void runDatabaseRestore(Runnable work) throws IOException {
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(collectionManager.getCurrentDataSource()));
        try {
            tx.executeWithoutResult(status -> work.run());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (RuntimeException e) {
            throw new IOException("Cannot restore portable user data", e);
        }
    }

    private void restoreDatabaseSectionsStreaming(Path sourceFile, BoundedIdentityCache ids,
                                                  RestoreCounters counters,
                                                  ReaderSettingsRestoreState readerSettings) throws IOException {
        Set<String> required = new LinkedHashSet<>(List.of("bookState", "readingProgress", "readingHistory",
                "readingStats", "bookmarks", "groups", "groupMemberships", "savedSearches", "readerSettings"));
        Set<String> seen = new HashSet<>();
        try (JsonParser parser = mapper.getFactory().createParser(sourceFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Portable user-data manifest must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken token = parser.nextToken();
                if (!required.contains(field)) {
                    parser.skipChildren();
                    continue;
                }
                if (!seen.add(field)) throw new IOException("Duplicate portable user-data section: " + field);
                if ("readerSettings".equals(field)) {
                    if (token != JsonToken.START_OBJECT) {
                        throw new IOException("Portable user-data section is not an object: " + field);
                    }
                    restoreReaderSettingsDatabaseStreaming(parser, ids, counters, readerSettings);
                    continue;
                }
                if (token != JsonToken.START_ARRAY) {
                    throw new IOException("Portable user-data section is not an array: " + field);
                }
                Consumer<JsonNode> rowConsumer = switch (field) {
                    case "bookState" -> row -> restoreBookStateRow(row, ids, counters);
                    case "readingProgress" -> row -> restoreReadingProgressRow(row, ids, counters);
                    case "readingHistory" -> row -> restoreReadingHistoryRow(row, ids, counters);
                    case "readingStats" -> row -> restoreReadingStatsRow(row, ids, counters);
                    case "bookmarks" -> row -> restoreBookmarkRow(row, ids, counters);
                    case "groups" -> row -> restoreGroupRow(row, counters);
                    case "groupMemberships" -> row -> restoreGroupMembershipRow(row, ids, counters);
                    case "savedSearches" -> row -> restoreSavedSearchRow(row, counters);
                    default -> throw new IllegalStateException("Unexpected section " + field);
                };
                streamArrayRows(parser, field, rowConsumer);
            }
        }
        required.removeAll(seen);
        if (!required.isEmpty()) throw new IOException("Portable user-data sections are missing: " + required);
    }

    private void streamArrayRows(JsonParser parser, String field, Consumer<JsonNode> consumer) throws IOException {
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            JsonNode row = mapper.readTree(parser);
            if (row == null || !row.isObject()) {
                throw new IOException("Portable user-data row is not an object in section: " + field);
            }
            consumer.accept(row);
        }
    }

    private void restoreNonDatabaseSectionsStreaming(Path sourceFile) throws IOException {
        try (JsonParser parser = mapper.getFactory().createParser(sourceFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Portable user-data manifest must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if ("filterSettings".equals(field)) {
                    JsonNode node = mapper.readTree(parser);
                    restoreFilterSettings(node);
                } else {
                    parser.skipChildren();
                }
            }
        }
    }

    private void restoreReaderSettingsDatabaseStreaming(JsonParser parser, BoundedIdentityCache ids,
                                                        RestoreCounters counters,
                                                        ReaderSettingsRestoreState state) throws IOException {
        Set<String> seen = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken token = parser.nextToken();
            if (!seen.add(field)) throw new IOException("Duplicate readerSettings field: " + field);
            if ("global".equals(field)) {
                if (token == JsonToken.VALUE_NULL) {
                    state.global(null);
                } else {
                    JsonNode global = mapper.readTree(parser);
                    state.global(validateReaderPreferencesNode(global, "readerSettings.global"));
                }
            } else if ("perBook".equals(field)) {
                if (token != JsonToken.START_ARRAY) {
                    throw new IOException("readerSettings.perBook must be an array");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    JsonNode row = mapper.readTree(parser);
                    if (row == null || !row.isObject()) throw new IOException("readerSettings.perBook row must be an object");
                    restoreReaderOverrideRow(row, ids, counters);
                }
            } else {
                parser.skipChildren();
            }
        }
        if (!seen.contains("perBook")) throw new IOException("readerSettings.perBook is missing");
    }

    private void restoreReaderOverrideRow(JsonNode row, BoundedIdentityCache ids, RestoreCounters counters) {
        Optional<String> bookId = resolveBookId(row, ids, counters);
        JsonNode preferences = row.get("preferences");
        if (bookId.isEmpty() || preferences == null || !preferences.isObject()) return;
        try {
            JsonNode validated = validateReaderPreferencesNode(preferences, "readerSettings.perBook.preferences");
            jdbc().update("""
                    INSERT INTO reader_book_preferences(book_id,preferences_json,updated_at)
                    VALUES(?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(book_id) DO UPDATE SET
                        preferences_json=excluded.preferences_json,updated_at=CURRENT_TIMESTAMP
                    """, bookId.get(), mapper.writeValueAsString(validated));
            counters.readerOverrides++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode validateReaderPreferencesNode(JsonNode node, String field) throws IOException {
        if (node == null || !node.isObject()) throw new IOException(field + " must be an object");
        byte[] encoded = mapper.writeValueAsBytes(node);
        if (encoded.length > MAX_READER_PREFERENCES_JSON_BYTES) {
            throw new IOException(field + " exceeds " + MAX_READER_PREFERENCES_JSON_BYTES + " bytes");
        }
        return node;
    }

    private void restoreReaderGlobal(JsonNode global) throws IOException {
        if (global == null || global.isNull()) return;
        fileLock.writeLock().lock();
        try {
            writeJsonAtomic(readerPreferencesFile, global);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private ImportResult importResult(int sourceVersion, int current, RestoreCounters counters) {
        return new ImportResult(sourceVersion, current, counters.matchedBooks, counters.unmatchedBooks,
                counters.groups, counters.groupMemberships, counters.bookmarks, counters.historyEntries,
                counters.savedSearches, counters.readerOverrides, counters.searchChanges.snapshot());
    }

    private record ManifestHeader(int sourceVersion, String format) { }

    private static final class ReaderSettingsRestoreState {
        private JsonNode global;
        JsonNode global() { return global; }
        void global(JsonNode value) { this.global = value; }
    }


    private void restoreBookStateRow(JsonNode n, BoundedIdentityCache ids, RestoreCounters c) {
        resolveBookId(n, ids, c).ifPresent(bookId -> {
            int updated = jdbc().update(
                    "UPDATE books SET rate=?, progress=?, review=? WHERE id=?",
                    safeInt(n, "rate", 0), safeInt(n, "progress", 0), limitedText(n, "review", ""), bookId);
            if (updated > 0) c.searchChanges.recordUpdated(bookId);
        });
    }



    private void restoreReadingProgressRow(JsonNode n, BoundedIdentityCache ids, RestoreCounters c) {
        resolveBookId(n, ids, c).ifPresent(bookId -> jdbc().update("""
                INSERT INTO reading_progress(book_id,paragraph_id,char_offset,percent,updated_at,anchor_id,paragraph_index)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(book_id) DO UPDATE SET paragraph_id=excluded.paragraph_id,char_offset=excluded.char_offset,
                    percent=excluded.percent,updated_at=excluded.updated_at,anchor_id=excluded.anchor_id,paragraph_index=excluded.paragraph_index
                """, bookId, limitedText(n, "paragraphId", ""), safeInt(n, "charOffset", 0),
                safeDouble(n, "percent", 0), limitedText(n, "updatedAt", Instant.EPOCH.toString()),
                safeNullableLimitedText(n, "anchorId"), safeInt(n, "paragraphIndex", 0)));
    }



    private void restoreReadingHistoryRow(JsonNode n, BoundedIdentityCache ids, RestoreCounters c) {
        resolveBookId(n, ids, c).ifPresent(bookId -> {
            jdbc().update("""
                    INSERT INTO reading_history(book_id,last_opened_at,open_count) VALUES(?,?,?)
                    ON CONFLICT(book_id) DO UPDATE SET last_opened_at=excluded.last_opened_at,open_count=excluded.open_count
                    """, bookId, limitedText(n, "lastOpenedAt", Instant.EPOCH.toString()),
                    Math.max(1, safeInt(n, "openCount", 1)));
            c.historyEntries++;
        });
    }



    private void restoreReadingStatsRow(JsonNode n, BoundedIdentityCache ids, RestoreCounters c) {
        resolveBookId(n, ids, c).ifPresent(bookId -> jdbc().update("""
                INSERT INTO reading_stats(book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,
                    start_percent,end_percent,current_percent,completed_at) VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(book_id) DO UPDATE SET
                    first_read_at=excluded.first_read_at,last_read_at=excluded.last_read_at,
                    total_reading_seconds=excluded.total_reading_seconds,reading_sessions=excluded.reading_sessions,
                    start_percent=excluded.start_percent,end_percent=excluded.end_percent,
                    current_percent=excluded.current_percent,completed_at=excluded.completed_at
                """, bookId, limitedText(n, "firstReadAt", Instant.EPOCH.toString()),
                limitedText(n, "lastReadAt", Instant.EPOCH.toString()), safeLong(n, "totalReadingSeconds", 0),
                safeInt(n, "readingSessions", 0), safeInt(n, "startPercent", 0), safeInt(n, "endPercent", 0),
                safeInt(n, "currentPercent", 0), safeNullableLimitedText(n, "completedAt")));
    }


    private void restoreBookmarkRow(JsonNode n, BoundedIdentityCache ids, RestoreCounters c) {
        resolveBookId(n, ids, c).ifPresent(bookId -> {
            String id = limitedText(n, "id", UUID.randomUUID().toString());
            jdbc().update("""
                    INSERT INTO bookmarks(id,book_id,paragraph_id,char_offset,position,chapter_title,context,created_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET book_id=excluded.book_id,paragraph_id=excluded.paragraph_id,
                        char_offset=excluded.char_offset,position=excluded.position,chapter_title=excluded.chapter_title,
                        context=excluded.context,created_at=excluded.created_at
                    """, id, bookId, limitedText(n, "paragraphId", ""), safeInt(n, "charOffset", 0),
                    safeDouble(n, "position", 0), safeNullableLimitedText(n, "chapterTitle"),
                    safeNullableLimitedText(n, "context"), limitedText(n, "createdAt", Instant.EPOCH.toString()));
            c.bookmarks++;
        });
    }



    private void restoreGroupRow(JsonNode n, RestoreCounters c) {
        String name = limitedText(n, "name", "").trim();
        if (name.isEmpty()) return;
        jdbc().update("""
                INSERT INTO groups(name,allow_delete) VALUES(?,?)
                ON CONFLICT(name) DO UPDATE SET allow_delete=excluded.allow_delete
                """, name, safeInt(n, "allowDelete", 1));
        c.groups++;
    }



    private void restoreGroupMembershipRow(JsonNode n, BoundedIdentityCache ids, RestoreCounters c) {
        resolveBookId(n, ids, c).ifPresent(bookId -> {
            String group = limitedText(n, "groupName", "").trim();
            if (group.isEmpty()) return;
            jdbc().update("INSERT OR IGNORE INTO groups(name,allow_delete) VALUES(?,1)", group);
            Integer groupId = jdbc().queryForObject("SELECT id FROM groups WHERE name=?", Integer.class, group);
            if (groupId != null) {
                jdbc().update("INSERT OR IGNORE INTO book_groups(book_id,group_id) VALUES(?,?)", bookId, groupId);
                c.groupMemberships++;
            }
        });
    }



    private void restoreSavedSearchRow(JsonNode n, RestoreCounters c) {
        String name = limitedText(n, "name", "").trim();
        if (name.isEmpty()) return;
        List<String> existing = jdbc().queryForList("SELECT id FROM saved_searches WHERE name=?", String.class, name);
        String id = existing.isEmpty() ? limitedText(n, "id", UUID.randomUUID().toString()) : existing.get(0);
        if (existing.isEmpty() && !jdbc().queryForList("SELECT id FROM saved_searches WHERE id=?", String.class, id).isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        jdbc().update("""
                INSERT INTO saved_searches(id,name,query,filters,created_at,last_used,use_count) VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(name) DO UPDATE SET query=excluded.query,filters=excluded.filters,
                    created_at=excluded.created_at,last_used=excluded.last_used,use_count=excluded.use_count
                """, id, name, limitedText(n, "query", ""), safeNullableLimitedText(n, "filters"),
                limitedText(n, "createdAt", Instant.EPOCH.toString()),
                limitedText(n, "lastUsed", Instant.EPOCH.toString()), safeInt(n, "useCount", 0));
        c.savedSearches++;
    }


    private void restoreFilterSettings(JsonNode node) {
        if (!node.isObject()) return;
        node.fields().forEachRemaining(e -> {
            if (!e.getKey().startsWith(FILTER_PREFIX)) return;
            String value = e.getValue().isNull() ? null : truncate(e.getValue().asText());
            if (value == null) settings.remove(e.getKey()); else settings.put(e.getKey(), value);
        });
    }


    private Optional<String> resolveBookId(JsonNode row, BoundedIdentityCache cache, RestoreCounters c) {
        String libId = safeText(row, "libId", "").trim();
        String sourceBookId = safeText(row, "sourceBookId", "").trim();
        String cacheKey = libId + "\u0000" + sourceBookId;
        Optional<String> cached = cache.get(cacheKey);
        if (cached != null) {
            if (cached.isPresent()) c.matchedBooks++; else c.unmatchedBooks++;
            return cached;
        }

        Optional<String> resolved = Optional.empty();
        boolean ambiguousLibId = false;
        if (!libId.isEmpty()) {
            List<String> byLibId = jdbc().queryForList(
                    "SELECT id FROM books WHERE lib_id=? ORDER BY id LIMIT 2", String.class, libId);
            if (byLibId.size() == 1) {
                resolved = Optional.ofNullable(byLibId.get(0));
            } else if (byLibId.size() > 1) {
                ambiguousLibId = true;
                if (!sourceBookId.isEmpty()) {
                    Integer exact = jdbc().queryForObject(
                            "SELECT COUNT(*) FROM books WHERE lib_id=? AND id=?", Integer.class, libId, sourceBookId);
                    if (exact != null && exact == 1) resolved = Optional.of(sourceBookId);
                }
            }
        }
        // Do not bypass an ambiguous stable identity with an unrelated internal id.
        if (resolved.isEmpty() && !ambiguousLibId && !sourceBookId.isEmpty()) {
            List<String> byInternalId = jdbc().queryForList(
                    "SELECT id FROM books WHERE id=? LIMIT 1", String.class, sourceBookId);
            if (!byInternalId.isEmpty()) resolved = Optional.ofNullable(byInternalId.get(0));
        }
        cache.put(cacheKey, resolved);
        if (resolved.isPresent()) c.matchedBooks++; else c.unmatchedBooks++;
        return resolved;
    }

    private static final class BoundedIdentityCache extends LinkedHashMap<String, Optional<String>> {
        private BoundedIdentityCache() { super(1024, 0.75f, true); }
        @Override protected boolean removeEldestEntry(Map.Entry<String, Optional<String>> eldest) {
            return size() > ID_CACHE_LIMIT;
        }
    }

    private static final class RestoreCounters {
        long matchedBooks; long unmatchedBooks; long groups; long groupMemberships;
        long bookmarks; long historyEntries; long savedSearches; long readerOverrides;
        final ImportChangeAccumulator searchChanges = ImportChangeAccumulator.withDefaultLimit();
    }

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
                        "ORDER BY b.id, rs.last_read_at, rs.id",
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
            JsonNode global = readJsonUnlocked(readerPreferencesFile);
            if (global == null) {
                g.writeNullField("global");
            } else {
                g.writeObjectField("global", validateReaderPreferencesNode(global, "readerSettings.global"));
            }
        } finally {
            fileLock.readLock().unlock();
        }

        g.writeArrayFieldStart("perBook");
        try {
            jdbc().query("""
                    SELECT b.lib_id, b.id, rbp.preferences_json
                    FROM reader_book_preferences rbp
                    JOIN books b ON b.id=rbp.book_id
                    ORDER BY b.id
                    """, rs -> {
                try {
                    while (rs.next()) {
                        String sourceBookId = rs.getString(2);
                        String json = rs.getString(3);
                        if (sourceBookId == null || sourceBookId.isBlank() || json == null || json.isBlank()) continue;
                        JsonNode preferences = validateReaderPreferencesNode(
                                mapper.readTree(json), "reader_book_preferences.preferences_json");
                        g.writeStartObject();
                        String libId = rs.getString(1);
                        if (libId == null || libId.isBlank()) g.writeNullField("libId"); else g.writeStringField("libId", libId);
                        g.writeStringField("sourceBookId", sourceBookId);
                        g.writeObjectField("preferences", preferences);
                        g.writeEndObject();
                        if (counter != null) counter.incrementAndGet();
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

    private void writeJsonAtomic(Path file, JsonNode value) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
        AtomicFileSupport.moveReplacing(tmp, file);
    }

    private JsonNode readJsonUnlocked(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            if (Files.size(file) > MAX_READER_PREFERENCES_JSON_BYTES) {
                log.warn("Cannot read {}: Reader preferences exceed {} bytes", file, MAX_READER_PREFERENCES_JSON_BYTES);
                return null;
            }
            return mapper.readTree(file.toFile());
        } catch (Exception e) {
            log.warn("Cannot read {}: {}", file, e.getMessage());
            return null;
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
    private static String limitedText(JsonNode n, String field, String fallback) {
        return truncate(safeText(n, field, fallback));
    }

    private static String safeNullableLimitedText(JsonNode n, String field) {
        String value = safeNullableText(n, field);
        return value == null ? null : truncate(value);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_STRING_LENGTH) return value;
        return value.substring(0, MAX_STRING_LENGTH);
    }

}