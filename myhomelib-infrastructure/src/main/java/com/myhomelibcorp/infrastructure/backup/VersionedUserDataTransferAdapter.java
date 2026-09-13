package com.myhomelibcorp.infrastructure.backup;

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
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        AtomicLong annotations = new AtomicLong();
        AtomicLong history = new AtomicLong();
        AtomicLong savedSearches = new AtomicLong();
        AtomicLong readerOverrides = new AtomicLong();

        try (JsonGenerator g = mapper.getFactory().createGenerator(tmp.toFile(), com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
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
            exportAnnotations(g, annotations);
            exportAnnotationTags(g);
            exportGroups(g, null);
            exportGroupMemberships(g, groupMemberships);
            exportSavedSearches(g, savedSearches);
            exportCustomFieldDefinitions(g);
            exportCustomFieldValues(g);

            g.writeFieldName("filterSettings");
            Map<String, String> filterSettings = settings.findByPrefix(FILTER_PREFIX);
            mapper.writeValue(g, filterSettings == null ? Map.of() : filterSettings);

            g.writeObjectFieldStart("readerSettings");
            exportReaderSettings(g, readerOverrides);
            g.writeEndObject();

            g.writeEndObject();
        } catch (UncheckedIOException e) {
            Files.deleteIfExists(tmp);
            throw portableExportFailure(e.getCause());
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw portableExportFailure(e);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw portableExportFailure(e);
        }

        try {
            AtomicFileSupport.moveReplacing(tmp, targetFile);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw portableExportFailure(e);
        }
        return new ExportResult(CURRENT_SCHEMA_VERSION, bookRecords.get(), groupMemberships.get(), bookmarks.get(),
                annotations.get(), history.get(), savedSearches.get(), readerOverrides.get());
    }


    private static IOException portableExportFailure(Exception cause) {
        String detail = cause == null || cause.getMessage() == null || cause.getMessage().isBlank()
                ? "unknown I/O failure"
                : cause.getMessage();
        return new IOException("Cannot export portable user data: " + detail, cause);
    }

    @Override
    public ImportResult restoreFrom(Path sourceFile) throws IOException {
        Objects.requireNonNull(sourceFile, "sourceFile");
        ManifestHeader header = inspectManifest(sourceFile);
        if (header.sourceVersion() >= 2 && header.sourceVersion() <= CURRENT_SCHEMA_VERSION) {
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
        ExternalStateSnapshot externalState = snapshotExternalState();
        try {
            runDatabaseRestore(() -> {
                try {
                    restoreLegacyV1DatabaseStreaming(sourceFile, identities, counters, readerSettings);
                    restoreNonDatabaseSectionsStreaming(sourceFile);
                    restoreReaderGlobal(readerSettings);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException failure) {
            rollbackExternalState(externalState, failure);
            throw failure;
        }
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
        Integer schemaVersion = null;
        Integer legacyVersion = null;
        String format = null;
        boolean schemaVersionSeen = false;
        boolean legacyVersionSeen = false;
        boolean formatSeen = false;

        try (JsonParser parser = mapper.getFactory().createParser(sourceFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Portable user-data manifest must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("schemaVersion".equals(field)) {
                    if (schemaVersionSeen) throw new IOException("Duplicate portable user-data field: schemaVersion");
                    schemaVersionSeen = true;
                    if (valueToken != JsonToken.VALUE_NUMBER_INT) {
                        throw new IOException("Portable user-data schemaVersion must be an integer");
                    }
                    schemaVersion = parser.getIntValue();
                } else if ("version".equals(field)) {
                    if (legacyVersionSeen) throw new IOException("Duplicate portable user-data field: version");
                    legacyVersionSeen = true;
                    if (valueToken != JsonToken.VALUE_NUMBER_INT) {
                        throw new IOException("Portable user-data version must be an integer");
                    }
                    legacyVersion = parser.getIntValue();
                } else if ("format".equals(field)) {
                    if (formatSeen) throw new IOException("Duplicate portable user-data field: format");
                    formatSeen = true;
                    if (valueToken != JsonToken.VALUE_STRING) {
                        throw new IOException("Portable user-data format must be a string");
                    }
                    format = parser.getValueAsString();
                }
                parser.skipChildren();
            }
        }

        if (schemaVersionSeen && legacyVersionSeen && !Objects.equals(schemaVersion, legacyVersion)) {
            throw new IOException("Conflicting portable user-data schema versions: schemaVersion="
                    + schemaVersion + ", version=" + legacyVersion);
        }
        if (!schemaVersionSeen && formatSeen) {
            throw new IOException("Portable user-data schemaVersion is required when format is present");
        }

        int version = schemaVersionSeen ? schemaVersion : legacyVersionSeen ? legacyVersion : 1;
        if (version < 1 || version > CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported portable user-data schema version: " + version);
        }
        if (version >= 2 && !"myhomelib-user-data".equals(format)) {
            throw new IOException(format == null
                    ? "Portable user-data format is missing for schema v" + version
                    : "Unsupported portable user-data format: " + format);
        }
        return new ManifestHeader(version, format);
    }

    /**
     * Performs a cheap streaming preflight of the current (v2+) manifest before any user data is changed.
     * Large arrays are skipped rather than materialized, but all required top-level sections and
     * reader-settings containers must have the expected shape.
     */
    private void validateCurrentManifestStructure(Path sourceFile, int sourceVersion) throws IOException {
        Map<String, JsonToken> required = new LinkedHashMap<>();
        for (String name : List.of("bookState", "readingProgress", "readingHistory", "readingStats",
                "bookmarks", "groups", "groupMemberships", "savedSearches")) {
            required.put(name, JsonToken.START_ARRAY);
        }
        if (sourceVersion >= 3) {
            required.put("customFieldDefinitions", JsonToken.START_ARRAY);
            required.put("customFieldValues", JsonToken.START_ARRAY);
        }
        if (sourceVersion >= 4) {
            required.put("annotations", JsonToken.START_ARRAY);
            required.put("annotationTags", JsonToken.START_ARRAY);
        }
        required.put("filterSettings", JsonToken.START_OBJECT);
        required.put("readerSettings", JsonToken.START_OBJECT);
        Set<String> seen = new HashSet<>();

        try (JsonParser parser = mapper.getFactory().createParser(sourceFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Portable user-data manifest must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken token = parser.nextToken();
                JsonToken expected = required.get(field);
                if (expected == null) {
                    parser.skipChildren();
                    continue;
                }
                if (!seen.add(field)) throw new IOException("Duplicate portable user-data section: " + field);
                if (token != expected) {
                    throw new IOException("Portable user-data section has invalid type: " + field);
                }
                if ("readerSettings".equals(field)) {
                    validateReaderSettingsStructure(parser);
                } else {
                    parser.skipChildren();
                }
            }
        }
        Set<String> missing = new LinkedHashSet<>(required.keySet());
        missing.removeAll(seen);
        if (!missing.isEmpty()) throw new IOException("Portable user-data sections are missing: " + missing);
    }

    private void validateReaderSettingsStructure(JsonParser parser) throws IOException {
        Set<String> seen = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken token = parser.nextToken();
            if (!seen.add(field)) throw new IOException("Duplicate readerSettings field: " + field);
            if ("global".equals(field)) {
                if (token != JsonToken.VALUE_NULL && token != JsonToken.START_OBJECT) {
                    throw new IOException("readerSettings.global must be an object or null");
                }
                parser.skipChildren();
            } else if ("perBook".equals(field)) {
                if (token != JsonToken.START_ARRAY) throw new IOException("readerSettings.perBook must be an array");
                parser.skipChildren();
            } else {
                parser.skipChildren();
            }
        }
        if (!seen.contains("global")) throw new IOException("readerSettings.global is missing");
        if (!seen.contains("perBook")) throw new IOException("readerSettings.perBook is missing");
    }

    private ExternalStateSnapshot snapshotExternalState() throws IOException {
        Map<String, String> filterSnapshot = new LinkedHashMap<>(settings.findByPrefix(FILTER_PREFIX));
        fileLock.readLock().lock();
        try {
            if (!Files.isRegularFile(readerPreferencesFile)) {
                return new ExternalStateSnapshot(filterSnapshot, new ReaderPreferencesSnapshot(false, new byte[0]));
            }
            long size = Files.size(readerPreferencesFile);
            if (size > MAX_READER_PREFERENCES_JSON_BYTES) {
                throw new IOException("Reader preferences exceed safety limit (" + size + " bytes): " + readerPreferencesFile);
            }
            return new ExternalStateSnapshot(filterSnapshot,
                    new ReaderPreferencesSnapshot(true, Files.readAllBytes(readerPreferencesFile)));
        } finally {
            fileLock.readLock().unlock();
        }
    }

    private void rollbackExternalState(ExternalStateSnapshot snapshot, IOException original) {
        try {
            settings.replaceByPrefix(FILTER_PREFIX, snapshot.filterSettings());
        } catch (RuntimeException settingsFailure) {
            original.addSuppressed(settingsFailure);
        }
        try {
            restoreReaderPreferencesSnapshot(snapshot.readerPreferences());
        } catch (IOException readerFailure) {
            original.addSuppressed(readerFailure);
        }
    }

    private void restoreReaderPreferencesSnapshot(ReaderPreferencesSnapshot snapshot) throws IOException {
        fileLock.writeLock().lock();
        try {
            Path tmp = readerPreferencesFile.resolveSibling(readerPreferencesFile.getFileName() + ".rollback.tmp");
            if (!snapshot.existed()) {
                Files.deleteIfExists(readerPreferencesFile);
                Files.deleteIfExists(tmp);
                return;
            }
            Files.createDirectories(readerPreferencesFile.toAbsolutePath().getParent());
            try {
                Files.write(tmp, snapshot.bytes());
                AtomicFileSupport.moveReplacing(tmp, readerPreferencesFile);
            } catch (IOException failure) {
                try { Files.deleteIfExists(tmp); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private ImportResult restoreCurrentSchemaStreaming(Path sourceFile, ManifestHeader header) throws IOException {
        validateCurrentManifestStructure(sourceFile, header.sourceVersion());
        RestoreCounters counters = new RestoreCounters();
        BoundedIdentityCache identities = new BoundedIdentityCache();
        ReaderSettingsRestoreState readerSettings = new ReaderSettingsRestoreState();
        ExternalStateSnapshot externalState = snapshotExternalState();
        try {
            runDatabaseRestore(() -> {
                try {
                    restoreDatabaseSectionsStreaming(sourceFile, identities, counters, readerSettings, header.sourceVersion());
                    restoreNonDatabaseSectionsStreaming(sourceFile);
                    restoreReaderGlobal(readerSettings);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException failure) {
            rollbackExternalState(externalState, failure);
            throw failure;
        }
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
                                                  ReaderSettingsRestoreState readerSettings, int sourceVersion) throws IOException {
        Set<String> required = new LinkedHashSet<>(List.of("bookState", "readingProgress", "readingHistory",
                "readingStats", "bookmarks", "groups", "groupMemberships", "savedSearches", "readerSettings"));
        if (sourceVersion >= 3) { required.add("customFieldDefinitions"); required.add("customFieldValues"); }
        if (sourceVersion >= 4) { required.add("annotations"); required.add("annotationTags"); }
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
                    case "annotations" -> row -> restoreAnnotationRow(row, ids, counters);
                    case "annotationTags" -> this::restoreAnnotationTagRow;
                    case "groups" -> row -> restoreGroupRow(row, counters);
                    case "groupMemberships" -> row -> restoreGroupMembershipRow(row, ids, counters);
                    case "savedSearches" -> row -> restoreSavedSearchRow(row, counters);
                    case "customFieldDefinitions" -> this::restoreCustomFieldDefinitionRow;
                    case "customFieldValues" -> row -> restoreCustomFieldValueRow(row, ids, counters);
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
        boolean filterSettingsSeen = false;
        try (JsonParser parser = mapper.getFactory().createParser(sourceFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Portable user-data manifest must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken token = parser.nextToken();
                if ("filterSettings".equals(field)) {
                    if (filterSettingsSeen) throw new IOException("Duplicate portable user-data section: filterSettings");
                    filterSettingsSeen = true;
                    if (token != JsonToken.START_OBJECT) {
                        throw new IOException("Portable user-data section is not an object: filterSettings");
                    }
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

    private void restoreReaderGlobal(ReaderSettingsRestoreState state) throws IOException {
        if (!state.globalSeen()) return;
        fileLock.writeLock().lock();
        try {
            JsonNode global = state.global();
            if (global == null || global.isNull()) {
                Files.deleteIfExists(readerPreferencesFile);
                Files.deleteIfExists(readerPreferencesFile.resolveSibling(readerPreferencesFile.getFileName() + ".tmp"));
            } else {
                writeJsonAtomic(readerPreferencesFile, global);
            }
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private ImportResult importResult(int sourceVersion, int current, RestoreCounters counters) {
        return new ImportResult(sourceVersion, current, counters.matchedBooks, counters.unmatchedBooks,
                counters.groups, counters.groupMemberships, counters.bookmarks, counters.annotations, counters.historyEntries,
                counters.savedSearches, counters.readerOverrides, counters.searchChanges.snapshot());
    }

    private record ManifestHeader(int sourceVersion, String format) { }
    private record ReaderPreferencesSnapshot(boolean existed, byte[] bytes) { }
    private record ExternalStateSnapshot(Map<String, String> filterSettings, ReaderPreferencesSnapshot readerPreferences) { }

    private static final class ReaderSettingsRestoreState {
        private JsonNode global;
        private boolean globalSeen;
        JsonNode global() { return global; }
        boolean globalSeen() { return globalSeen; }
        void global(JsonNode value) { this.global = value; this.globalSeen = true; }
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
                INSERT INTO reading_progress(book_id,paragraph_id,char_offset,percent,updated_at,anchor_id,paragraph_index,last_device)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(book_id) DO UPDATE SET paragraph_id=excluded.paragraph_id,char_offset=excluded.char_offset,
                    percent=excluded.percent,updated_at=excluded.updated_at,anchor_id=excluded.anchor_id,
                    paragraph_index=excluded.paragraph_index,last_device=excluded.last_device
                """, bookId, limitedText(n, "paragraphId", ""), safeInt(n, "charOffset", 0),
                safeDouble(n, "percent", 0), limitedText(n, "updatedAt", Instant.EPOCH.toString()),
                safeNullableLimitedText(n, "anchorId"), safeInt(n, "paragraphIndex", 0),
                limitedText(n, "lastDevice", "desktop")));
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



    private void restoreAnnotationRow(JsonNode n, BoundedIdentityCache ids, RestoreCounters c) {
        if (!hasTableColumn("annotations", "id") || !hasTableColumn("annotation_anchors", "annotation_id")) {
            throw new IllegalStateException("Target database must be migrated to V57 before restoring annotations");
        }
        resolveBookId(n, ids, c).ifPresent(bookId -> {
            String id = limitedText(n, "id", UUID.randomUUID().toString()).trim();
            if (id.isEmpty()) id = UUID.randomUUID().toString();
            String type = limitedText(n, "type", "").trim().toUpperCase(Locale.ROOT);
            if (!type.equals("HIGHLIGHT") && !type.equals("NOTE")) {
                throw new IllegalArgumentException("Unsupported restored annotation type: " + type);
            }
            String color = limitedText(n, "color", "#FFF59D").trim().toUpperCase(Locale.ROOT);
            if (!color.matches("#[0-9A-F]{6}([0-9A-F]{2})?")) {
                throw new IllegalArgumentException("Invalid restored annotation color");
            }
            String note = limitedText(n, "note", "");
            String artifactId = safeNullableLimitedText(n, "artifactId");
            if (artifactId != null) {
                Integer linked = jdbc().queryForObject(
                        "SELECT COUNT(*) FROM book_artifacts WHERE book_id=? AND artifact_id=?", Integer.class,
                        bookId, artifactId);
                if (linked == null || linked != 1) artifactId = null;
            }
            long start = Math.max(0L, safeLong(n, "startOffset", 0L));
            long end = Math.max(start, safeLong(n, "endOffset", start));
            if ("HIGHLIGHT".equals(type) && end == start) {
                throw new IllegalArgumentException("Restored highlight requires a non-empty text range");
            }
            String quote = limitedText(n, "quote", "");
            if ("HIGHLIGHT".equals(type) && quote.isBlank()) {
                throw new IllegalArgumentException("Restored highlight requires selected quote text");
            }
            if ("NOTE".equals(type) && note.isBlank()) {
                throw new IllegalArgumentException("Restored note annotation requires note text");
            }
            double position = Math.max(0.0, Math.min(1.0, safeDouble(n, "position", 0.0)));
            String createdAt = limitedText(n, "createdAt", Instant.EPOCH.toString());
            String updatedAt = limitedText(n, "updatedAt", createdAt);
            Instant createdInstant = Instant.parse(createdAt);
            Instant updatedInstant = Instant.parse(updatedAt);
            if (updatedInstant.isBefore(createdInstant)) {
                throw new IllegalArgumentException("Restored annotation updatedAt cannot precede createdAt");
            }
            // annotationTags is an exact snapshot, not an append-only stream. Clearing here makes
            // repeated restore idempotent and removes tags that were deleted in the exported state.
            jdbc().update("DELETE FROM annotation_tags WHERE annotation_id=?", id);
            jdbc().update("""
                    INSERT INTO annotations(id,book_id,artifact_id,annotation_type,color,note,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET book_id=excluded.book_id,artifact_id=excluded.artifact_id,
                        annotation_type=excluded.annotation_type,color=excluded.color,note=excluded.note,
                        created_at=excluded.created_at,updated_at=excluded.updated_at
                    """, id, bookId, artifactId, type, color, note, createdAt, updatedAt);
            jdbc().update("""
                    INSERT INTO annotation_anchors(annotation_id,chapter_id,chapter_title,paragraph_id,start_offset,end_offset,
                        position,quote_text,prefix_text,suffix_text)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(annotation_id) DO UPDATE SET chapter_id=excluded.chapter_id,
                        chapter_title=excluded.chapter_title,paragraph_id=excluded.paragraph_id,
                        start_offset=excluded.start_offset,end_offset=excluded.end_offset,position=excluded.position,
                        quote_text=excluded.quote_text,prefix_text=excluded.prefix_text,suffix_text=excluded.suffix_text
                    """, id, safeNullableLimitedText(n, "chapterId"), safeNullableLimitedText(n, "chapterTitle"),
                    safeNullableLimitedText(n, "paragraphId"), start, end, position,
                    quote, limitedText(n, "prefix", ""), limitedText(n, "suffix", ""));
            c.annotations++;
        });
    }

    private void restoreAnnotationTagRow(JsonNode n) {
        if (!hasTableColumn("annotation_tags", "annotation_id")) {
            throw new IllegalStateException("Target database must be migrated to V57 before restoring annotation tags");
        }
        String annotationId = limitedText(n, "annotationId", "").trim();
        String tag = limitedText(n, "tag", "").trim();
        if (annotationId.isEmpty() || tag.isEmpty()) return;
        jdbc().update("""
                INSERT OR IGNORE INTO annotation_tags(annotation_id,tag)
                SELECT ?,? WHERE EXISTS(SELECT 1 FROM annotations WHERE id=?)
                """, annotationId, tag, annotationId);
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
        String kind = limitedText(n, "kind", "SEARCH").trim();
        if (!kind.equals("SEARCH") && !kind.equals("SMART_COLLECTION")) kind = "SEARCH";
        int pinned = safeInt(n, "pinned", 0) == 0 ? 0 : 1;
        boolean smartSchema = hasTableColumn("saved_searches", "kind")
                && hasTableColumn("saved_searches", "pinned");
        if (!smartSchema) {
            if (kind.equals("SMART_COLLECTION")) {
                throw new IllegalStateException("Target database must be migrated to V54 before restoring smart collections");
            }
            jdbc().update("""
                    INSERT INTO saved_searches(id,name,query,filters,created_at,last_used,use_count) VALUES(?,?,?,?,?,?,?)
                    ON CONFLICT(name) DO UPDATE SET query=excluded.query,filters=excluded.filters,
                        created_at=excluded.created_at,last_used=excluded.last_used,use_count=excluded.use_count
                    """, id, name, limitedText(n, "query", ""), safeNullableLimitedText(n, "filters"),
                    limitedText(n, "createdAt", Instant.EPOCH.toString()),
                    limitedText(n, "lastUsed", Instant.EPOCH.toString()), safeInt(n, "useCount", 0));
        } else {
            jdbc().update("""
                    INSERT INTO saved_searches(id,name,query,filters,created_at,last_used,use_count,kind,pinned) VALUES(?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(name) DO UPDATE SET query=excluded.query,filters=excluded.filters,
                        created_at=excluded.created_at,last_used=excluded.last_used,use_count=excluded.use_count,
                        kind=excluded.kind,pinned=excluded.pinned
                    """, id, name, limitedText(n, "query", ""), safeNullableLimitedText(n, "filters"),
                    limitedText(n, "createdAt", Instant.EPOCH.toString()),
                    limitedText(n, "lastUsed", Instant.EPOCH.toString()), safeInt(n, "useCount", 0), kind, pinned);
        }
        c.savedSearches++;
    }


    private void restoreCustomFieldDefinitionRow(JsonNode n) {
        String name = limitedText(n, "name", "").trim();
        String type = limitedText(n, "fieldType", "TEXT").trim();
        String options = safeNullableLimitedText(n, "enumOptionsJson");
        if (name.isEmpty()) return;
        if (!Set.of("TEXT","NUMBER","BOOL","DATE","ENUM").contains(type)) {
            throw new IllegalArgumentException("Unsupported custom field type in backup: " + type);
        }
        if (options == null || options.isBlank()) options = "[]";
        jdbc().update("""
                INSERT INTO custom_field_definitions(name,field_type,enum_options_json,updated_at)
                VALUES(?,?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(name) DO UPDATE SET field_type=excluded.field_type,enum_options_json=excluded.enum_options_json,updated_at=CURRENT_TIMESTAMP
                """, name, type, options);
    }

    private void restoreCustomFieldValueRow(JsonNode n, BoundedIdentityCache ids, RestoreCounters c) {
        resolveBookId(n, ids, c).ifPresent(bookId -> {
            String fieldName = limitedText(n, "fieldName", "").trim();
            String value = limitedText(n, "value", "");
            if (fieldName.isEmpty() || value.isBlank()) return;
            List<Long> definitionIds = jdbc().queryForList(
                    "SELECT id FROM custom_field_definitions WHERE name=? COLLATE NOCASE", Long.class, fieldName);
            if (definitionIds.isEmpty()) throw new IllegalStateException("Custom field definition missing during restore: " + fieldName);
            jdbc().update("""
                    INSERT INTO custom_field_values(book_id,definition_id,value_text,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(book_id,definition_id) DO UPDATE SET value_text=excluded.value_text,updated_at=CURRENT_TIMESTAMP
                    """, bookId, definitionIds.getFirst(), value);
        });
    }

    private void restoreFilterSettings(JsonNode node) throws IOException {
        if (node == null || !node.isObject()) {
            throw new IOException("filterSettings must be an object");
        }
        Map<String, String> restored = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getKey().startsWith(FILTER_PREFIX)) continue;
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || valueNode.isNull()) continue;
            if (!valueNode.isValueNode()) {
                throw new IOException("filterSettings value must be scalar: " + entry.getKey());
            }
            restored.put(entry.getKey(), truncate(valueNode.asText()));
        }
        settings.replaceByPrefix(FILTER_PREFIX, restored);
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
        long bookmarks; long annotations; long historyEntries; long savedSearches; long readerOverrides;
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
                        "rp.anchor_id AS anchorId, COALESCE(rp.paragraph_index,0) AS paragraphIndex, " +
                        "COALESCE(NULLIF(TRIM(rp.last_device),''),'desktop') AS lastDevice " +
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

    private void exportAnnotations(JsonGenerator g, AtomicLong counter) throws IOException {
        if (!hasTableColumn("annotations", "id") || !hasTableColumn("annotation_anchors", "annotation_id")) {
            g.writeArrayFieldStart("annotations"); g.writeEndArray(); return;
        }
        writeRows(g, "annotations",
                "SELECT a.id AS id, b.lib_id AS libId, b.id AS sourceBookId, a.artifact_id AS artifactId, " +
                        "a.annotation_type AS type, a.color AS color, a.note AS note, " +
                        "x.chapter_id AS chapterId, x.chapter_title AS chapterTitle, x.paragraph_id AS paragraphId, " +
                        "x.start_offset AS startOffset, x.end_offset AS endOffset, x.position AS position, " +
                        "x.quote_text AS quote, x.prefix_text AS prefix, x.suffix_text AS suffix, " +
                        "a.created_at AS createdAt, a.updated_at AS updatedAt " +
                        "FROM annotations a JOIN annotation_anchors x ON x.annotation_id=a.id " +
                        "JOIN books b ON b.id=a.book_id ORDER BY a.created_at,a.id",
                counter);
    }

    private void exportAnnotationTags(JsonGenerator g) throws IOException {
        if (!hasTableColumn("annotation_tags", "annotation_id")) {
            g.writeArrayFieldStart("annotationTags"); g.writeEndArray(); return;
        }
        writeRows(g, "annotationTags",
                "SELECT annotation_id AS annotationId, tag FROM annotation_tags ORDER BY annotation_id,tag COLLATE NOCASE",
                null);
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
        boolean hasKind = hasTableColumn("saved_searches", "kind");
        boolean hasPinned = hasTableColumn("saved_searches", "pinned");
        String kind = hasKind ? "COALESCE(kind,'SEARCH')" : "'SEARCH'";
        String pinned = hasPinned ? "COALESCE(pinned,0)" : "0";
        String order = hasPinned ? "pinned DESC, name" : "name";
        writeRows(g, "savedSearches",
                "SELECT id, name, query, filters, created_at AS createdAt, " +
                        "last_used AS lastUsed, COALESCE(use_count,0) AS useCount, " +
                        kind + " AS kind, " + pinned + " AS pinned " +
                        "FROM saved_searches ORDER BY " + order,
                counter);
    }

    private void exportCustomFieldDefinitions(JsonGenerator g) throws IOException {
        if (!hasTableColumn("custom_field_definitions", "id")) {
            g.writeArrayFieldStart("customFieldDefinitions"); g.writeEndArray(); return;
        }
        writeRows(g, "customFieldDefinitions",
                "SELECT name, field_type AS fieldType, enum_options_json AS enumOptionsJson FROM custom_field_definitions ORDER BY name COLLATE NOCASE,id",
                null);
    }

    private void exportCustomFieldValues(JsonGenerator g) throws IOException {
        if (!hasTableColumn("custom_field_values", "book_id")) {
            g.writeArrayFieldStart("customFieldValues"); g.writeEndArray(); return;
        }
        writeRows(g, "customFieldValues",
                "SELECT b.lib_id AS libId, b.id AS sourceBookId, d.name AS fieldName, v.value_text AS value " +
                        "FROM custom_field_values v JOIN books b ON b.id=v.book_id " +
                        "JOIN custom_field_definitions d ON d.id=v.definition_id ORDER BY b.id,d.id",
                null);
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
                    """, (ResultSetExtractor<Void>) rs -> {
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
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        g.writeEndArray();
    }


    private void writeRows(JsonGenerator g, String field, String sql, AtomicLong counter) throws IOException {
        g.writeArrayFieldStart(field);
        try {
            jdbc().query(sql, (ResultSetExtractor<Void>) rs -> {
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
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        g.writeEndArray();
    }

    private void writeJsonAtomic(Path file, JsonNode value) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            AtomicFileSupport.moveReplacing(tmp, file);
        } catch (IOException failure) {
            try { Files.deleteIfExists(tmp); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private JsonNode readJsonUnlocked(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;
        long size = Files.size(file);
        if (size > MAX_READER_PREFERENCES_JSON_BYTES) {
            throw new IOException("Reader preferences exceed safety limit (" + size + " bytes): " + file);
        }
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new IOException("Cannot read Reader preferences: " + file, e);
        }
    }


    private JdbcTemplate jdbc() {
        if (!collectionManager.hasActiveCollection()) {
            throw new IllegalStateException("No active collection");
        }
        return collectionManager.getCurrentJdbcTemplate();
    }

    private boolean hasTableColumn(String table, String column) {
        return jdbc().queryForList("PRAGMA table_info(" + table + ")").stream()
                .map(row -> row.get("name"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .anyMatch(column::equalsIgnoreCase);
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