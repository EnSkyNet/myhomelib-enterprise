package com.myhomelibcorp.infrastructure.reader;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.reader.ReaderBookPreferencesPort;
import com.myhomelibcorp.application.port.out.reader.ReaderScopedPreferencesPort;
import com.myhomelibcorp.application.reader.ReaderPreferenceScope;
import com.myhomelibcorp.application.reader.ReaderPreferenceScopeType;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.domain.event.collection.CollectionOpenedEvent;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Collection-scoped per-book Reader overrides.
 *
 * <p>v7.1 originally stored all overrides in one global JSON object and therefore
 * read/rewrote the full file for every load/save/delete. V42 moves the canonical
 * state into the active collection DB. The old JSON remains a read-only migration
 * source: each collection imports only book ids that exist in that collection and
 * records a DB-local marker after a complete bounded pass.</p>
 */
@Service
@Slf4j
public class ReaderBookPreferencesService implements ReaderBookPreferencesPort, ReaderScopedPreferencesPort {
    private static final String LEGACY_MIGRATION_MARKER = "v71_reader_book_preferences_json_migrated";
    private static final int LEGACY_MIGRATION_BATCH = 400;

    @Autowired
    public ReaderBookPreferencesService(CollectionManager collectionManager, ObjectMapper objectMapper,
                                        ReaderPreferencesJsonCodec codec,
                                        LibraryOperationCoordinator operationCoordinator) {
        this.collectionManager = collectionManager;
        this.objectMapper = objectMapper;
        this.streamingNodeReader = objectMapper.reader()
                .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.codec = codec;
        this.operationCoordinator = operationCoordinator;
    }

    /** Compatibility constructor for isolated persistence tests without lifecycle wiring. */
    ReaderBookPreferencesService(CollectionManager collectionManager, ObjectMapper objectMapper,
                                 ReaderPreferencesJsonCodec codec) {
        this(collectionManager, objectMapper, codec, null);
    }

    private final CollectionManager collectionManager;
    private final ObjectMapper objectMapper;
    private final ObjectReader streamingNodeReader;
    private final ReaderPreferencesJsonCodec codec;
    private final LibraryOperationCoordinator operationCoordinator;
    private final Object migrationLock = new Object();
    private final Path legacyFile = AppPaths.configDir().resolve("reader-book-preferences.json");

    @EventListener
    public void onCollectionOpened(CollectionOpenedEvent event) {
        if (event == null || event.getCollection() == null) return;
        withReaderState(() -> {
            if (collectionManager.hasActiveCollection()) ensureLegacyMigrated();
            return null;
        });
    }

    @Override
    public Optional<ReaderPreferences> load(String bookId) {
        ReaderPreferenceScope scope = ReaderPreferenceScope.tryBook(bookId);
        return scope == null ? Optional.empty() : load(scope);
    }

    @Override
    public void save(String bookId, ReaderPreferences preferences) {
        ReaderPreferenceScope scope = ReaderPreferenceScope.tryBook(bookId);
        if (scope != null) save(scope, preferences);
    }

    @Override
    public void delete(String bookId) {
        ReaderPreferenceScope scope = ReaderPreferenceScope.tryBook(bookId);
        if (scope != null) delete(scope);
    }

    @Override
    public Optional<ReaderPreferences> load(ReaderPreferenceScope scope) {
        if (scope == null) return Optional.empty();
        return withReaderState(() -> {
            if (!collectionManager.hasActiveCollection()) return Optional.empty();
            ensureLegacyMigrated();
            final String json;
            try {
                json = jdbc().queryForObject(
                        "SELECT preferences_json FROM reader_preference_overrides WHERE scope_type=? AND scope_key=?",
                        String.class, scope.type().name(), scope.key());
            } catch (EmptyResultDataAccessException e) {
                return Optional.empty();
            } catch (DataAccessException e) {
                throw new IllegalStateException("Не вдалося прочитати Reader settings для " + scope, e);
            }
            if (json == null || json.isBlank()) return Optional.empty();
            try {
                return Optional.of(codec.decode(json));
            } catch (Exception malformed) {
                log.warn("Ігноруємо пошкоджений Reader profile {} і використовуємо нижчий scope: {}",
                        scope, malformed.getMessage());
                return Optional.empty();
            }
        });
    }

    @Override
    public void save(ReaderPreferenceScope scope, ReaderPreferences preferences) {
        if (scope == null || preferences == null) return;
        withReaderState(() -> {
            if (!collectionManager.hasActiveCollection()) return null;
            ensureLegacyMigrated();
            try {
                String json = codec.encode(preferences);
                jdbc().update("""
                        INSERT INTO reader_preference_overrides(scope_type,scope_key,preferences_json,updated_at)
                        VALUES(?,?,?,CURRENT_TIMESTAMP)
                        ON CONFLICT(scope_type,scope_key) DO UPDATE SET
                            preferences_json=excluded.preferences_json,
                            updated_at=CURRENT_TIMESTAMP
                        """, scope.type().name(), scope.key(), json);
                // Keep the released V42 table synchronized for backward-compatible tooling.
                if (scope.type() == ReaderPreferenceScopeType.BOOK) {
                    jdbc().update("""
                            INSERT INTO reader_book_preferences(book_id,preferences_json,updated_at)
                            VALUES(?,?,CURRENT_TIMESTAMP)
                            ON CONFLICT(book_id) DO UPDATE SET
                                preferences_json=excluded.preferences_json,
                                updated_at=CURRENT_TIMESTAMP
                            """, scope.key(), json);
                }
                return null;
            } catch (Exception e) {
                throw new IllegalStateException("Не вдалося зберегти Reader settings для " + scope, e);
            }
        });
    }

    @Override
    public void delete(ReaderPreferenceScope scope) {
        if (scope == null) return;
        withReaderState(() -> {
            if (!collectionManager.hasActiveCollection()) return null;
            ensureLegacyMigrated();
            jdbc().update("DELETE FROM reader_preference_overrides WHERE scope_type=? AND scope_key=?",
                    scope.type().name(), scope.key());
            if (scope.type() == ReaderPreferenceScopeType.BOOK) {
                jdbc().update("DELETE FROM reader_book_preferences WHERE book_id=?", scope.key());
            }
            return null;
        });
    }

    private void ensureLegacyMigrated() {
        JdbcTemplate jdbc = jdbc();
        if (migrationCompleted(jdbc)) return;

        synchronized (migrationLock) {
            if (migrationCompleted(jdbc)) return;
            try {
                long migrated = 0L;
                if (Files.exists(legacyFile, LinkOption.NOFOLLOW_LINKS)) {
                    Path safeLegacy = SafeFilePublication.requireSafeRegularFile(
                            legacyFile, "legacy per-book Reader preferences");
                    migrated = migrateLegacyFile(jdbc, safeLegacy);
                }
                jdbc.update("""
                        INSERT INTO settings(key,value) VALUES(?,?)
                        ON CONFLICT(key) DO UPDATE SET value=excluded.value
                        """, LEGACY_MIGRATION_MARKER, "1");
                if (migrated > 0) {
                    log.info("Мігровано {} legacy per-book Reader overrides у активну колекцію", migrated);
                }
            } catch (Exception e) {
                // Do not write the marker: an interrupted/corrupt migration is safely retryable.
                log.warn("Не вдалося завершити міграцію legacy per-book Reader settings: {}", e.getMessage());
            }
        }
    }

    private boolean migrationCompleted(JdbcTemplate jdbc) {
        try {
            String marker = jdbc.query(
                    "SELECT value FROM settings WHERE key=?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    LEGACY_MIGRATION_MARKER);
            return "1".equals(marker);
        } catch (DataAccessException persistenceFailure) {
            // During very early collection startup V42 may not have created the settings table yet.
            // Only that expected pre-migration state is equivalent to "not migrated". A locked,
            // corrupt or otherwise unreadable collection must remain a real persistence failure.
            if (isMissingSettingsTable(persistenceFailure)) return false;
            throw new IllegalStateException("Не вдалося перевірити стан міграції Reader settings", persistenceFailure);
        }
    }

    private static boolean isMissingSettingsTable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("no such table") && normalized.contains("settings")) return true;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private long migrateLegacyFile(JdbcTemplate jdbc, Path safeLegacyFile) throws Exception {
        long migrated = 0L;
        try (java.io.InputStream input = Files.newInputStream(
                     safeLegacyFile, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             JsonParser parser = objectMapper.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("legacy reader-book-preferences.json is not a JSON object");
            }
            List<LegacyOverride> batch = new ArrayList<>(LEGACY_MIGRATION_BATCH);
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String bookId = parser.currentName();
                parser.nextToken();
                JsonNode node = streamingNodeReader.readTree(parser);
                if (bookId == null || bookId.isBlank() || node == null || !node.isObject()) continue;
                // Normalize missing legacy fields against current domain defaults.
                batch.add(new LegacyOverride(bookId, codec.encode(codec.decode(node))));
                if (batch.size() >= LEGACY_MIGRATION_BATCH) {
                    migrated += migrateLegacyBatch(jdbc, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) migrated += migrateLegacyBatch(jdbc, batch);
        }
        return migrated;
    }

    private long migrateLegacyBatch(JdbcTemplate jdbc, List<LegacyOverride> batch) {
        if (batch.isEmpty()) return 0L;
        String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
        Object[] ids = batch.stream().map(LegacyOverride::bookId).toArray();
        Set<String> existing = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM books WHERE id IN (" + placeholders + ")", String.class, ids));
        if (existing.isEmpty()) return 0L;

        List<Object[]> inserts = batch.stream()
                .filter(entry -> existing.contains(entry.bookId()))
                .map(entry -> new Object[]{entry.bookId(), entry.preferencesJson()})
                .toList();
        if (inserts.isEmpty()) return 0L;

        int[] counts = jdbc.batchUpdate("""
                INSERT INTO reader_preference_overrides(scope_type,scope_key,preferences_json,updated_at)
                VALUES('BOOK',?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(scope_type,scope_key) DO NOTHING
                """, inserts);
        // Mirror into the released V42 table for backward-compatible export/tools.
        jdbc.batchUpdate("""
                INSERT INTO reader_book_preferences(book_id,preferences_json,updated_at)
                VALUES(?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(book_id) DO NOTHING
                """, inserts);
        long changed = 0L;
        for (int count : counts) if (count > 0) changed += count;
        return changed;
    }

    private <T> T withReaderState(Supplier<T> action) {
        if (operationCoordinator == null || operationCoordinator.isHeldByCurrentThread()) {
            return action.get();
        }
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.READER_STATE);
        try {
            T result = action.get();
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private JdbcTemplate jdbc() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    private record LegacyOverride(String bookId, String preferencesJson) { }
}
