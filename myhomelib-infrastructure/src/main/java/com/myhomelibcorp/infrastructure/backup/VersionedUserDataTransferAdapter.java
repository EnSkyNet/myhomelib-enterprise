package com.myhomelibcorp.infrastructure.backup;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.backup.UserDataTransferPort;
import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.reader.ReaderPreferenceScope;
import com.myhomelibcorp.application.reader.ReaderPreferenceScopeType;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Component
public class VersionedUserDataTransferAdapter implements UserDataTransferPort {

    private static final String FILTER_PREFIX = "filter.global.";
    private static final int ID_CACHE_LIMIT = 50_000;
    private static final int MAX_STRING_LENGTH = 10000;
    private static final String SQLITE_EPOCH = "1970-01-01 00:00:00.000";

    private final CollectionManager collectionManager;
    private final ApplicationSettingsPort settings;
    private final ObjectMapper mapper;
    private final ObjectReader streamingNodeReader;
    private final PortableUserDataManifestValidator manifestValidator;
    private final ReaderPreferencesTransferStore readerPreferences;

    public VersionedUserDataTransferAdapter(CollectionManager collectionManager,
                                            ApplicationSettingsPort settings,
                                            ObjectMapper mapper) {
        this.collectionManager = collectionManager;
        this.settings = settings;
        this.mapper = mapper;
        // Jackson 3 enables FAIL_ON_TRAILING_TOKENS by default. That is desirable for
        // complete JSON documents, but not when reading one value from an already
        // positioned streaming parser whose next token belongs to the surrounding array.
        this.streamingNodeReader = mapper.reader()
                .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.manifestValidator = new PortableUserDataManifestValidator(mapper);
        this.readerPreferences = new ReaderPreferencesTransferStore(mapper);
    }

    @Override
    public ExportResult exportTo(Path targetFile) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile");
        Path safeTarget = SafeFilePublication.prepareTarget(targetFile, "Portable user-data export destination");
        String expectedTargetHash = SafeFilePublication.snapshotExistingTarget(
                safeTarget, "Portable user-data export destination");
        Path workspace = SafeFilePublication.createPrivateWorkspace(
                safeTarget.getParent(), ".mhl-user-data-export-", "Portable user-data export workspace");
        Path staged = workspace.resolve("portable-user-data.json");
        AtomicLong bookRecords = new AtomicLong();
        AtomicLong groupMemberships = new AtomicLong();
        AtomicLong bookmarks = new AtomicLong();
        AtomicLong annotations = new AtomicLong();
        AtomicLong history = new AtomicLong();
        AtomicLong savedSearches = new AtomicLong();
        AtomicLong readerOverrides = new AtomicLong();

        try {
            try (OutputStream out = SafeFilePublication.openFreshOutputStream(
                         staged, "Portable user-data staged export");
                 JsonGenerator g = mapper.writerWithDefaultPrettyPrinter()
                         .createGenerator(out, tools.jackson.core.JsonEncoding.UTF8)) {
                g.writeStartObject();
                g.writeNumberProperty("schemaVersion", CURRENT_SCHEMA_VERSION);
                g.writeStringProperty("format", "myhomelib-user-data");
                g.writeStringProperty("exportedAt", Instant.now().toString());

                exportBookState(g, bookRecords);
                exportReadingProgress(g, null);
                exportReadingHistory(g, history);
                exportReadingStats(g, null);
                exportBookmarks(g, bookmarks);
                exportAnnotations(g, annotations);
                exportAnnotationTags(g);
                exportGroups(g, null);
                exportGroupMemberships(g, groupMemberships);
                exportReadingLists(g);
                exportReadingListItems(g);
                exportSavedSearches(g, savedSearches);
                exportCustomFieldDefinitions(g);
                exportCustomFieldValues(g);

                g.writeName("filterSettings");
                Map<String, String> filterSettings = settings.findByPrefix(FILTER_PREFIX);
                mapper.writeValue(g, filterSettings == null ? Map.of() : filterSettings);

                g.writeObjectPropertyStart("readerSettings");
                exportReaderSettings(g, readerOverrides);
                g.writeEndObject();

                g.writeEndObject();
            }
            SafeFilePublication.publishReplacingIfUnchanged(
                    staged, safeTarget, expectedTargetHash, "Portable user-data export");
        } catch (UncheckedIOException e) {
            throw portableExportFailure(e.getCause());
        } catch (IOException e) {
            throw portableExportFailure(e);
        } catch (Exception e) {
            throw portableExportFailure(e);
        } finally {
            try {
                SafeFilePublication.cleanupPrivateWorkspace(workspace);
            } catch (IOException cleanupFailure) {
                log.warn("Cannot clean portable user-data export workspace {}: {}", workspace, cleanupFailure.toString());
            }
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
        Path safeSource = SafeFilePublication.requireSafeRegularFile(
                sourceFile, "Portable user-data import source");
        Path workspace = SafeFilePublication.createPrivateWorkspace(
                AppPaths.cacheDir(), ".mhl-user-data-import-", "Portable user-data import workspace");
        Path stableSource = workspace.resolve("portable-user-data.json");
        try {
            try (InputStream in = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.copyFresh(in, stableSource, "Portable user-data stable import snapshot");
            }
            String snapshotHash = SafeFilePublication.sha256(
                    stableSource, "Portable user-data stable import snapshot");
            String currentSourceHash = SafeFilePublication.sha256(
                    safeSource, "Portable user-data import source after snapshot");
            if (!snapshotHash.equalsIgnoreCase(currentSourceHash)) {
                throw new IOException("Portable user-data import source changed while it was being snapshotted");
            }

            PortableUserDataManifestValidator.ManifestHeader header =
                    manifestValidator.inspect(stableSource, CURRENT_SCHEMA_VERSION);
            if (header.sourceVersion() >= 2 && header.sourceVersion() <= CURRENT_SCHEMA_VERSION) {
                return restoreCurrentSchemaStreaming(stableSource, header);
            }
            return restoreLegacyManifest(stableSource, header.sourceVersion());
        } catch (JacksonException e) {
            throw new IOException("Cannot parse portable user-data JSON", e);
        } finally {
            try {
                SafeFilePublication.cleanupPrivateWorkspace(workspace);
            } catch (IOException cleanupFailure) {
                log.warn("Cannot clean portable user-data import workspace {}: {}", workspace, cleanupFailure.toString());
            }
        }
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
                    restoreReaderGlobal(readerSettings, externalState.readerPreferences());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException failure) {
            rollbackExternalState(externalState, readerSettings, failure);
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
        try (JsonParser parser = mapper.createParser(sourceFile)) {
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
                    restoreReaderSettingsDatabaseStreaming(parser, ids, counters, readerSettings, 1);
                } else {
                    parser.skipChildren();
                }
            }
        }
    }

    private ExternalStateSnapshot snapshotExternalState() throws IOException {
        Map<String, String> filterSnapshot = new LinkedHashMap<>(settings.findByPrefix(FILTER_PREFIX));
        return new ExternalStateSnapshot(filterSnapshot, readerPreferences.snapshot());
    }

    private void rollbackExternalState(ExternalStateSnapshot snapshot,
                                       ReaderSettingsRestoreState readerState,
                                       IOException original) {
        try {
            settings.replaceByPrefix(FILTER_PREFIX, snapshot.filterSettings());
        } catch (RuntimeException settingsFailure) {
            original.addSuppressed(settingsFailure);
        }
        try {
            readerPreferences.rollback(snapshot.readerPreferences(),
                    readerState.readerPreferencesChanged(),
                    readerState.installedReaderPreferencesHash());
        } catch (IOException readerFailure) {
            original.addSuppressed(readerFailure);
        }
    }

    private ImportResult restoreCurrentSchemaStreaming(
            Path sourceFile, PortableUserDataManifestValidator.ManifestHeader header) throws IOException {
        manifestValidator.validateCurrentStructure(sourceFile, header.sourceVersion());
        RestoreCounters counters = new RestoreCounters();
        BoundedIdentityCache identities = new BoundedIdentityCache();
        ReaderSettingsRestoreState readerSettings = new ReaderSettingsRestoreState();
        ExternalStateSnapshot externalState = snapshotExternalState();
        try {
            runDatabaseRestore(() -> {
                try {
                    if (header.sourceVersion() >= 5) {
                        restoreReadingListsBeforeStreaming(sourceFile);
                    }
                    restoreDatabaseSectionsStreaming(sourceFile, identities, counters, readerSettings, header.sourceVersion());
                    if (header.sourceVersion() >= 5) {
                        compactReadingListPositions();
                    }
                    restoreNonDatabaseSectionsStreaming(sourceFile);
                    restoreReaderGlobal(readerSettings, externalState.readerPreferences());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException failure) {
            rollbackExternalState(externalState, readerSettings, failure);
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
        if (sourceVersion >= 5) { required.add("readingLists"); required.add("readingListItems"); }
        Set<String> seen = new HashSet<>();
        try (JsonParser parser = mapper.createParser(sourceFile)) {
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
                    restoreReaderSettingsDatabaseStreaming(parser, ids, counters, readerSettings, sourceVersion);
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
                    case "readingLists" -> row -> { /* metadata restored in fail-before-item prepass */ };
                    case "readingListItems" -> row -> restoreReadingListItemRow(row, ids, counters);
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
            JsonNode row = streamingNodeReader.readTree(parser);
            if (row == null || !row.isObject()) {
                throw new IOException("Portable user-data row is not an object in section: " + field);
            }
            consumer.accept(row);
        }
    }

    private void restoreNonDatabaseSectionsStreaming(Path sourceFile) throws IOException {
        boolean filterSettingsSeen = false;
        try (JsonParser parser = mapper.createParser(sourceFile)) {
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
                    JsonNode node = streamingNodeReader.readTree(parser);
                    restoreFilterSettings(node);
                } else {
                    parser.skipChildren();
                }
            }
        }
    }

    private void restoreReaderSettingsDatabaseStreaming(JsonParser parser, BoundedIdentityCache ids,
                                                        RestoreCounters counters,
                                                        ReaderSettingsRestoreState state, int sourceVersion) throws IOException {
        Set<String> seen = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken token = parser.nextToken();
            if (!seen.add(field)) throw new IOException("Duplicate readerSettings field: " + field);
            if ("global".equals(field)) {
                if (token == JsonToken.VALUE_NULL) {
                    state.global(null);
                } else {
                    JsonNode global = streamingNodeReader.readTree(parser);
                    state.global(validateReaderPreferencesNode(global, "readerSettings.global"));
                }
            } else if ("perBook".equals(field)) {
                if (token != JsonToken.START_ARRAY) {
                    throw new IOException("readerSettings.perBook must be an array");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    JsonNode row = streamingNodeReader.readTree(parser);
                    if (row == null || !row.isObject()) throw new IOException("readerSettings.perBook row must be an object");
                    if (sourceVersion < 6) restoreReaderOverrideRow(row, ids, counters);
                }
            } else if ("scoped".equals(field)) {
                if (token != JsonToken.START_ARRAY) {
                    throw new IOException("readerSettings.scoped must be an array");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    JsonNode row = streamingNodeReader.readTree(parser);
                    if (row == null || !row.isObject()) throw new IOException("readerSettings.scoped row must be an object");
                    if (sourceVersion >= 6) restoreReaderScopedOverrideRow(row, ids, counters);
                }
            } else {
                parser.skipChildren();
            }
        }
        if (!seen.contains("perBook")) throw new IOException("readerSettings.perBook is missing");
        if (sourceVersion >= 6 && !seen.contains("scoped")) throw new IOException("readerSettings.scoped is missing");
    }

    private void restoreReaderOverrideRow(JsonNode row, BoundedIdentityCache ids, RestoreCounters counters) {
        Optional<String> bookId = resolveBookId(row, ids, counters);
        JsonNode preferences = row.get("preferences");
        if (bookId.isEmpty() || preferences == null || !preferences.isObject()) return;
        try {
            JsonNode validated = validateReaderPreferencesNode(preferences, "readerSettings.perBook.preferences");
            String json = mapper.writeValueAsString(validated);
            jdbc().update("""
                    INSERT INTO reader_book_preferences(book_id,preferences_json,updated_at)
                    VALUES(?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(book_id) DO UPDATE SET
                        preferences_json=excluded.preferences_json,updated_at=CURRENT_TIMESTAMP
                    """, bookId.get(), json);
            jdbc().update("""
                    INSERT INTO reader_preference_overrides(scope_type,scope_key,preferences_json,updated_at)
                    VALUES('BOOK',?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(scope_type,scope_key) DO UPDATE SET
                        preferences_json=excluded.preferences_json,updated_at=CURRENT_TIMESTAMP
                    """, bookId.get(), json);
            counters.readerOverrides++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void restoreReaderScopedOverrideRow(JsonNode row, BoundedIdentityCache ids, RestoreCounters counters) {
        String typeText = safeText(row, "scopeType", "").trim();
        JsonNode preferences = row.get("preferences");
        if (typeText.isBlank() || preferences == null || !preferences.isObject()) return;
        final ReaderPreferenceScopeType type;
        try {
            type = ReaderPreferenceScopeType.valueOf(typeText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return;
        }

        try {
            ReaderPreferenceScope scope;
            if (type == ReaderPreferenceScopeType.BOOK) {
                Optional<String> bookId = resolveBookId(row, ids, counters);
                if (bookId.isEmpty()) return;
                scope = ReaderPreferenceScope.book(bookId.get());
            } else {
                String key = safeText(row, "scopeKey", "");
                scope = type == ReaderPreferenceScopeType.SERIES
                        ? ReaderPreferenceScope.trySeries(key) : ReaderPreferenceScope.tryFormat(key);
                if (scope == null) return;
            }
            JsonNode validated = validateReaderPreferencesNode(preferences, "readerSettings.scoped.preferences");
            String json = mapper.writeValueAsString(validated);
            jdbc().update("""
                    INSERT INTO reader_preference_overrides(scope_type,scope_key,preferences_json,updated_at)
                    VALUES(?,?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(scope_type,scope_key) DO UPDATE SET
                        preferences_json=excluded.preferences_json,updated_at=CURRENT_TIMESTAMP
                    """, scope.type().name(), scope.key(), json);
            if (scope.type() == ReaderPreferenceScopeType.BOOK) {
                jdbc().update("""
                        INSERT INTO reader_book_preferences(book_id,preferences_json,updated_at)
                        VALUES(?,?,CURRENT_TIMESTAMP)
                        ON CONFLICT(book_id) DO UPDATE SET
                            preferences_json=excluded.preferences_json,updated_at=CURRENT_TIMESTAMP
                        """, scope.key(), json);
            }
            counters.readerOverrides++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode validateReaderPreferencesNode(JsonNode node, String field) throws IOException {
        return readerPreferences.validateNode(node, field);
    }

    private void restoreReaderGlobal(ReaderSettingsRestoreState state,
                                     ReaderPreferencesTransferStore.Snapshot baseline) throws IOException {
        if (!state.globalSeen()) return;
        String installedHash = readerPreferences.restoreGlobal(state.global(), baseline);
        state.markReaderPreferencesChanged(installedHash);
    }

    private ImportResult importResult(int sourceVersion, int current, RestoreCounters counters) {
        return new ImportResult(sourceVersion, current, counters.matchedBooks, counters.unmatchedBooks,
                counters.groups, counters.groupMemberships, counters.bookmarks, counters.annotations, counters.historyEntries,
                counters.savedSearches, counters.readerOverrides, counters.searchChanges.snapshot());
    }

    private record ExternalStateSnapshot(Map<String, String> filterSettings, ReaderPreferencesTransferStore.Snapshot readerPreferences) { }

    private static final class ReaderSettingsRestoreState {
        private JsonNode global;
        private boolean globalSeen;
        private boolean readerPreferencesChanged;
        private String installedReaderPreferencesHash;
        JsonNode global() { return global; }
        boolean globalSeen() { return globalSeen; }
        void global(JsonNode value) { this.global = value; this.globalSeen = true; }
        boolean readerPreferencesChanged() { return readerPreferencesChanged; }
        String installedReaderPreferencesHash() { return installedReaderPreferencesHash; }
        void markReaderPreferencesChanged(String installedHash) {
            this.readerPreferencesChanged = true;
            this.installedReaderPreferencesHash = installedHash;
        }
    }


    private void restoreBookStateRow(JsonNode n, BoundedIdentityCache ids, RestoreCounters c) {
        resolveBookId(n, ids, c).ifPresent(bookId -> {
            int updated = jdbc().update(
                    "UPDATE books SET rate=?, progress=?, review=? WHERE id=?",
                    safeInt(n, "rate", 0), percentInt(n, "progress", 0), limitedText(n, "review", ""), bookId);
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
                """, bookId, limitedText(n, "paragraphId", ""), nonNegativeInt(n, "charOffset", 0),
                percentDouble(n, "percent", 0), limitedText(n, "updatedAt", Instant.EPOCH.toString()),
                safeNullableLimitedText(n, "anchorId"), nonNegativeInt(n, "paragraphIndex", 0),
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
                limitedText(n, "lastReadAt", Instant.EPOCH.toString()), nonNegativeLong(n, "totalReadingSeconds", 0),
                nonNegativeInt(n, "readingSessions", 0), percentInt(n, "startPercent", 0), percentInt(n, "endPercent", 0),
                percentInt(n, "currentPercent", 0), safeNullableLimitedText(n, "completedAt")));
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
                    """, id, bookId, limitedText(n, "paragraphId", ""), nonNegativeInt(n, "charOffset", 0),
                    percentDouble(n, "position", 0), safeNullableLimitedText(n, "chapterTitle"),
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
            // Annotation presence is part of the derived Lucene document schema (Iteration 85).
            // Mark the logical book as changed so portable restore refreshes has_note/has_highlight.
            c.searchChanges.recordUpdated(bookId);
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


    /**
     * Reads V5 list metadata in a dedicated first pass so readingListItems may appear
     * anywhere in the manifest without depending on top-level section order.
     */
    private void restoreReadingListsBeforeStreaming(Path sourceFile) throws IOException {
        boolean seen = false;
        try (JsonParser parser = mapper.createParser(sourceFile)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Portable user-data manifest must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken token = parser.nextToken();
                if (!"readingLists".equals(field)) {
                    parser.skipChildren();
                    continue;
                }
                if (seen) throw new IOException("Duplicate portable user-data section: readingLists");
                seen = true;
                if (token != JsonToken.START_ARRAY) {
                    throw new IOException("Portable user-data section is not an array: readingLists");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    JsonNode row = streamingNodeReader.readTree(parser);
                    if (row == null || !row.isObject()) {
                        throw new IOException("Portable user-data row is not an object in section: readingLists");
                    }
                    try {
                        restoreReadingListRow(row);
                    } catch (UncheckedIOException e) {
                        throw e.getCause();
                    }
                }
            }
        }
        if (!seen) throw new IOException("Portable user-data sections are missing: [readingLists]");
    }

    private void restoreReadingListRow(JsonNode n) {
        String sourceId = limitedText(n, "id", "").trim();
        String name = limitedText(n, "name", "").trim();
        if (sourceId.isEmpty() || name.isEmpty()) {
            throw new UncheckedIOException(new IOException("readingLists rows require non-empty id and name"));
        }
        String description = limitedText(n, "description", "");
        String createdAt = limitedText(n, "createdAt", SQLITE_EPOCH);
        String updatedAt = limitedText(n, "updatedAt", createdAt);

        List<String> byName = jdbc().queryForList(
                "SELECT id FROM reading_lists WHERE name=? COLLATE NOCASE LIMIT 2", String.class, name);
        if (!byName.isEmpty() && !sourceId.equals(byName.getFirst())) {
            throw new UncheckedIOException(new IOException(
                    "Reading-list name collision during portable restore: " + name));
        }
        List<String> byId = jdbc().queryForList(
                "SELECT id FROM reading_lists WHERE id=? LIMIT 1", String.class, sourceId);
        if (byId.isEmpty()) {
            jdbc().update("INSERT INTO reading_lists(id,name,description,created_at,updated_at) VALUES(?,?,?,?,?)",
                    sourceId, name, description, createdAt, updatedAt);
        } else {
            jdbc().update("UPDATE reading_lists SET name=?,description=?,created_at=?,updated_at=? WHERE id=?",
                    name, description, createdAt, updatedAt, sourceId);
        }
        // V5 restore is an exact replacement for the exported list membership.
        jdbc().update("DELETE FROM reading_list_items WHERE list_id=?", sourceId);
    }

    private void restoreReadingListItemRow(JsonNode n, BoundedIdentityCache ids, RestoreCounters c) {
        String listId = limitedText(n, "listId", "").trim();
        if (listId.isEmpty()) {
            throw new UncheckedIOException(new IOException("readingListItems rows require listId"));
        }
        List<String> lists = jdbc().queryForList(
                "SELECT id FROM reading_lists WHERE id=? LIMIT 1", String.class, listId);
        if (lists.isEmpty()) {
            throw new UncheckedIOException(new IOException(
                    "readingListItems references an unknown listId: " + listId));
        }
        int position = safeInt(n, "position", -1);
        if (position < 0) {
            throw new UncheckedIOException(new IOException("readingListItems position must be >= 0"));
        }
        Optional<String> bookId = resolveBookId(n, ids, c);
        if (bookId.isEmpty()) return; // Stable identity did not resolve; never substitute by title/fuzzy match.
        String addedAt = limitedText(n, "addedAt", SQLITE_EPOCH);
        try {
            // Plain INSERT deliberately rejects duplicate membership and duplicate source positions.
            jdbc().update("INSERT INTO reading_list_items(list_id,book_id,position,added_at) VALUES(?,?,?,?)",
                    listId, bookId.get(), position, addedAt);
        } catch (RuntimeException failure) {
            throw new UncheckedIOException(new IOException(
                    "Invalid readingListItems membership/position for list " + listId, failure));
        }
    }

    /** Compacts positions after unresolved stable identities were skipped, preserving source order. */
    private void compactReadingListPositions() {
        jdbc().update("UPDATE reading_list_items SET position = -position - 1");
        jdbc().update("""
                UPDATE reading_list_items AS current
                SET position = (
                    SELECT COUNT(*)
                    FROM reading_list_items AS prior
                    WHERE prior.list_id=current.list_id AND prior.position>current.position
                )
                """);
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
        for (var entry : node.properties()) {
            if (!entry.getKey().startsWith(FILTER_PREFIX)) continue;
            JsonNode valueNode = entry.getValue();
            if (valueNode == null || valueNode.isNull()) continue;
            if (!valueNode.isValueNode()) {
                throw new IOException("filterSettings value must be scalar: " + entry.getKey());
            }
            restored.put(entry.getKey(), truncate(valueNode.asString()));
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
            g.writeArrayPropertyStart("annotations"); g.writeEndArray(); return;
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
            g.writeArrayPropertyStart("annotationTags"); g.writeEndArray(); return;
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

    private void exportReadingLists(JsonGenerator g) throws IOException {
        writeRows(g, "readingLists",
                "SELECT id,name,description,created_at AS createdAt,updated_at AS updatedAt " +
                        "FROM reading_lists ORDER BY LOWER(name),id", null);
    }

    private void exportReadingListItems(JsonGenerator g) throws IOException {
        writeRows(g, "readingListItems",
                "SELECT rl.id AS listId,b.lib_id AS libId,b.id AS sourceBookId," +
                        "rli.position AS position,rli.added_at AS addedAt " +
                        "FROM reading_list_items rli " +
                        "JOIN reading_lists rl ON rl.id=rli.list_id " +
                        "JOIN books b ON b.id=rli.book_id " +
                        "ORDER BY LOWER(rl.name),rl.id,rli.position", null);
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
            g.writeArrayPropertyStart("customFieldDefinitions"); g.writeEndArray(); return;
        }
        writeRows(g, "customFieldDefinitions",
                "SELECT name, field_type AS fieldType, enum_options_json AS enumOptionsJson FROM custom_field_definitions ORDER BY name COLLATE NOCASE,id",
                null);
    }

    private void exportCustomFieldValues(JsonGenerator g) throws IOException {
        if (!hasTableColumn("custom_field_values", "book_id")) {
            g.writeArrayPropertyStart("customFieldValues"); g.writeEndArray(); return;
        }
        writeRows(g, "customFieldValues",
                "SELECT b.lib_id AS libId, b.id AS sourceBookId, d.name AS fieldName, v.value_text AS value " +
                        "FROM custom_field_values v JOIN books b ON b.id=v.book_id " +
                        "JOIN custom_field_definitions d ON d.id=v.definition_id ORDER BY b.id,d.id",
                null);
    }

    private void exportReaderSettings(JsonGenerator g, AtomicLong counter) throws IOException {
        JsonNode global = readerPreferences.readForExport();
        if (global == null) {
            g.writeNullProperty("global");
        } else {
            g.writePOJOProperty("global", validateReaderPreferencesNode(global, "readerSettings.global"));
        }

        // Retain the V2-V5 projection for backwards inspection/compatibility. V6 restore uses `scoped`.
        g.writeArrayPropertyStart("perBook");
        try {
            jdbc().query("""
                    SELECT b.lib_id, b.id, rbp.preferences_json
                    FROM reader_book_preferences rbp
                    JOIN books b ON b.id=rbp.book_id
                    ORDER BY b.id
                    """, (ResultSetExtractor<Void>) rs -> {
                try {
                    while (rs.next()) {
                        writePortableBookReaderOverride(g, rs.getString(1), rs.getString(2), rs.getString(3));
                    }
                } catch (IOException | JacksonException e) {
                    throw new UncheckedIOException(e instanceof IOException io
                            ? io : new IOException("Cannot serialize reader book preferences", e));
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        g.writeEndArray();

        g.writeArrayPropertyStart("scoped");
        try {
            jdbc().query("""
                    SELECT r.scope_type,r.scope_key,r.preferences_json,b.lib_id
                    FROM reader_preference_overrides r
                    LEFT JOIN books b ON r.scope_type='BOOK' AND b.id=r.scope_key
                    ORDER BY r.scope_type,r.scope_key
                    """, (ResultSetExtractor<Void>) rs -> {
                try {
                    while (rs.next()) {
                        String type = rs.getString(1);
                        String key = rs.getString(2);
                        String json = rs.getString(3);
                        if (type == null || key == null || key.isBlank() || json == null || json.isBlank()) continue;
                        JsonNode preferences = validateReaderPreferencesNode(
                                mapper.readTree(json), "reader_preference_overrides.preferences_json");
                        g.writeStartObject();
                        g.writeStringProperty("scopeType", type);
                        if ("BOOK".equalsIgnoreCase(type)) {
                            String libId = rs.getString(4);
                            if (libId == null || libId.isBlank()) g.writeNullProperty("libId"); else g.writeStringProperty("libId", libId);
                            g.writeStringProperty("sourceBookId", key);
                        } else {
                            g.writeStringProperty("scopeKey", key);
                        }
                        g.writePOJOProperty("preferences", preferences);
                        g.writeEndObject();
                        if (counter != null) counter.incrementAndGet();
                    }
                } catch (IOException | JacksonException e) {
                    throw new UncheckedIOException(e instanceof IOException io
                            ? io : new IOException("Cannot serialize scoped reader preferences", e));
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        g.writeEndArray();
    }

    private void writePortableBookReaderOverride(JsonGenerator g, String libId, String sourceBookId, String json)
            throws IOException {
        if (sourceBookId == null || sourceBookId.isBlank() || json == null || json.isBlank()) return;
        JsonNode preferences = validateReaderPreferencesNode(
                mapper.readTree(json), "reader_book_preferences.preferences_json");
        g.writeStartObject();
        if (libId == null || libId.isBlank()) g.writeNullProperty("libId"); else g.writeStringProperty("libId", libId);
        g.writeStringProperty("sourceBookId", sourceBookId);
        g.writePOJOProperty("preferences", preferences);
        g.writeEndObject();
    }

    private void writeRows(JsonGenerator g, String field, String sql, AtomicLong counter) throws IOException {
        g.writeArrayPropertyStart(field);
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
                                g.writeNullProperty(name);
                            } else if (value instanceof Number || value instanceof Boolean) {
                                g.writePOJOProperty(name, value);
                            } else {
                                g.writeStringProperty(name, value.toString());
                            }
                        }
                        g.writeEndObject();
                        if (counter != null) {
                            counter.incrementAndGet();
                        }
                    }
                } catch (JacksonException e) {
                    throw new UncheckedIOException(new IOException("Cannot serialize portable user-data row", e));
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        g.writeEndArray();
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
        return v.asString();
    }

    private static String safeNullableText(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        return v.asString();
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
        double value = v.asDouble(defaultValue);
        return Double.isFinite(value) ? value : defaultValue;
    }

    private static int nonNegativeInt(JsonNode n, String field, int defaultValue) {
        return Math.max(0, safeInt(n, field, defaultValue));
    }

    private static long nonNegativeLong(JsonNode n, String field, long defaultValue) {
        return Math.max(0L, safeLong(n, field, defaultValue));
    }

    private static int percentInt(JsonNode n, String field, int defaultValue) {
        return Math.max(0, Math.min(100, safeInt(n, field, defaultValue)));
    }

    private static double percentDouble(JsonNode n, String field, double defaultValue) {
        return Math.max(0.0, Math.min(100.0, safeDouble(n, field, defaultValue)));
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
        int end = MAX_STRING_LENGTH;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

}