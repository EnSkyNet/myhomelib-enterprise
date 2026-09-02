package com.myhomelibcorp.infrastructure.reader;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.reader.ReaderBookPreferencesPort;
import com.myhomelibcorp.domain.event.collection.CollectionOpenedEvent;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
@RequiredArgsConstructor
@Slf4j
public class ReaderBookPreferencesService implements ReaderBookPreferencesPort {
    private static final String LEGACY_MIGRATION_MARKER = "v71_reader_book_preferences_json_migrated";
    private static final int LEGACY_MIGRATION_BATCH = 400;

    private final CollectionManager collectionManager;
    private final ObjectMapper objectMapper;
    private final ReaderPreferencesJsonCodec codec;
    private final Object migrationLock = new Object();
    private final Path legacyFile = AppPaths.configDir().resolve("reader-book-preferences.json");

    @EventListener
    public void onCollectionOpened(CollectionOpenedEvent event) {
        if (event != null && event.getCollection() != null && collectionManager.hasActiveCollection()) {
            ensureLegacyMigrated();
        }
    }

    @Override
    public Optional<ReaderPreferences> load(String bookId) {
        if (bookId == null || bookId.isBlank() || !collectionManager.hasActiveCollection()) {
            return Optional.empty();
        }
        ensureLegacyMigrated();
        try {
            String json = jdbc().queryForObject(
                    "SELECT preferences_json FROM reader_book_preferences WHERE book_id=?",
                    String.class, bookId);
            if (json == null || json.isBlank()) return Optional.empty();
            return Optional.of(codec.decode(json));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося прочитати per-book Reader settings для " + bookId, e);
        }
    }

    @Override
    public void save(String bookId, ReaderPreferences preferences) {
        if (bookId == null || bookId.isBlank() || preferences == null || !collectionManager.hasActiveCollection()) {
            return;
        }
        ensureLegacyMigrated();
        try {
            String json = codec.encode(preferences);
            jdbc().update("""
                    INSERT INTO reader_book_preferences(book_id,preferences_json,updated_at)
                    VALUES(?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(book_id) DO UPDATE SET
                        preferences_json=excluded.preferences_json,
                        updated_at=CURRENT_TIMESTAMP
                    """, bookId, json);
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося зберегти per-book Reader settings для " + bookId, e);
        }
    }

    @Override
    public void delete(String bookId) {
        if (bookId == null || bookId.isBlank() || !collectionManager.hasActiveCollection()) return;
        ensureLegacyMigrated();
        jdbc().update("DELETE FROM reader_book_preferences WHERE book_id=?", bookId);
    }

    private void ensureLegacyMigrated() {
        JdbcTemplate jdbc = jdbc();
        if (migrationCompleted(jdbc)) return;

        synchronized (migrationLock) {
            if (migrationCompleted(jdbc)) return;
            try {
                long migrated = Files.isRegularFile(legacyFile) ? migrateLegacyFile(jdbc) : 0L;
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
        } catch (Exception e) {
            // During very early collection startup V42 may not have been applied yet.
            return false;
        }
    }

    private long migrateLegacyFile(JdbcTemplate jdbc) throws Exception {
        long migrated = 0L;
        try (JsonParser parser = objectMapper.getFactory().createParser(legacyFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("legacy reader-book-preferences.json is not a JSON object");
            }
            List<LegacyOverride> batch = new ArrayList<>(LEGACY_MIGRATION_BATCH);
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String bookId = parser.currentName();
                parser.nextToken();
                JsonNode node = objectMapper.readTree(parser);
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
                INSERT INTO reader_book_preferences(book_id,preferences_json,updated_at)
                VALUES(?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(book_id) DO NOTHING
                """, inserts);
        long changed = 0L;
        for (int count : counts) if (count > 0) changed += count;
        return changed;
    }

    private JdbcTemplate jdbc() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    private record LegacyOverride(String bookId, String preferencesJson) { }
}
