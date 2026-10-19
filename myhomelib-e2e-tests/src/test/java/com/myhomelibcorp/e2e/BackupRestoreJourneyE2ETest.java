package com.myhomelibcorp.e2e;

import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.backup.UserDataTransferPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.infrastructure.backup.VersionedUserDataTransferAdapter;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackupRestoreJourneyE2ETest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void portableUserDataRoundTripRestoresStateToSameLibIdWithDifferentInternalId() throws Exception {
        Path dataDir = tempDir.resolve("appdata-roundtrip");
        System.setProperty("myhomelib.dataDir", dataDir.toString());
        Files.createDirectories(dataDir.resolve("config"));
        Files.writeString(dataDir.resolve("config/reader-preferences.json"), "{\"fontFamily\":\"Serif\"}");

        Db source = db(tempDir.resolve("source.db"));
        createSchema(source.jdbc());
        source.jdbc().update("INSERT INTO books(id,lib_id,rate,progress,review) VALUES('old-id','LIB-42',8,61,'source-review')");
        source.jdbc().update("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,percent,updated_at,anchor_id,text_anchor_id,audio_anchor_id,paragraph_index) VALUES('old-id','p-7',17,61.5,'2026-09-06T08:00:00Z','a-7','text-a-7','audio-a-7',2)");
        source.jdbc().update("INSERT INTO groups(name,allow_delete) VALUES('Favorites',0)");
        source.jdbc().update("INSERT INTO book_groups(book_id,group_id) SELECT 'old-id',id FROM groups WHERE name='Favorites'");
        source.jdbc().update("INSERT INTO reader_book_preferences(book_id,preferences_json) VALUES('old-id','{\"fontFamily\":\"Mono\"}')");
        source.jdbc().update("INSERT INTO reader_preference_overrides(scope_type,scope_key,preferences_json) VALUES('BOOK','old-id','{\"fontFamily\":\"Mono\"}'),('SERIES','series e2e','{\"theme\":\"dark\"}'),('FORMAT','epub','{\"fontFamily\":\"Serif\"}')");
        source.jdbc().update("INSERT INTO reading_lists(id,name,description) VALUES('list-1','Queue','E2E')");
        source.jdbc().update("INSERT INTO reading_list_items(list_id,book_id,position) VALUES('list-1','old-id',0)");

        MapSettings sourceSettings = new MapSettings();
        sourceSettings.put("filter.global.language", "uk");
        Path manifest = tempDir.resolve("portable-user-data.json");
        VersionedUserDataTransferAdapter sourceAdapter = new VersionedUserDataTransferAdapter(
                manager(source), sourceSettings, new ObjectMapper());
        var exported = sourceAdapter.exportTo(manifest);
        assertThat(exported.schemaVersion()).isEqualTo(UserDataTransferPort.CURRENT_SCHEMA_VERSION);
        assertThat(manifest).isRegularFile();

        Db target = db(tempDir.resolve("target.db"));
        createSchema(target.jdbc());
        target.jdbc().update("INSERT INTO books(id,lib_id,rate,progress,review) VALUES('new-id','LIB-42',0,0,NULL)");
        MapSettings targetSettings = new MapSettings();
        VersionedUserDataTransferAdapter targetAdapter = new VersionedUserDataTransferAdapter(
                manager(target), targetSettings, new ObjectMapper());

        var restored = targetAdapter.restoreFrom(manifest);
        assertThat(restored.effectiveSchemaVersion()).isEqualTo(UserDataTransferPort.CURRENT_SCHEMA_VERSION);
        assertThat(target.jdbc().queryForObject("SELECT rate FROM books WHERE id='new-id'", Integer.class)).isEqualTo(8);
        assertThat(target.jdbc().queryForObject("SELECT progress FROM books WHERE id='new-id'", Integer.class)).isEqualTo(61);
        assertThat(target.jdbc().queryForObject("SELECT paragraph_id FROM reading_progress WHERE book_id='new-id'", String.class)).isEqualTo("p-7");
        // V71 migration preserves independent text/audio resume anchors on portable restores.
        assertThat(target.jdbc().queryForObject("SELECT text_anchor_id FROM reading_progress WHERE book_id='new-id'", String.class)).isEqualTo("text-a-7");
        assertThat(target.jdbc().queryForObject("SELECT audio_anchor_id FROM reading_progress WHERE book_id='new-id'", String.class)).isEqualTo("audio-a-7");
        assertThat(target.jdbc().queryForObject("SELECT COUNT(*) FROM book_groups WHERE book_id='new-id'", Integer.class)).isEqualTo(1);
        assertThat(target.jdbc().queryForObject("SELECT name FROM reading_lists WHERE id='list-1'", String.class)).isEqualTo("Queue");
        assertThat(target.jdbc().queryForObject("SELECT book_id FROM reading_list_items WHERE list_id='list-1' AND position=0", String.class)).isEqualTo("new-id");
        assertThat(target.jdbc().queryForObject("SELECT preferences_json FROM reader_preference_overrides WHERE scope_type='BOOK' AND scope_key='new-id'", String.class)).contains("Mono");
        assertThat(target.jdbc().queryForObject("SELECT preferences_json FROM reader_preference_overrides WHERE scope_type='SERIES' AND scope_key='series e2e'", String.class)).contains("dark");
        assertThat(target.jdbc().queryForObject("SELECT preferences_json FROM reader_preference_overrides WHERE scope_type='FORMAT' AND scope_key='epub'", String.class)).contains("Serif");
        assertThat(targetSettings.get("filter.global.language", "")).isEqualTo("uk");
    }

    @Test
    void restoreCompensatesDatabaseSettingsAndReaderPreferencesWhenSettingsPersistenceFails() throws Exception {
        Path dataDir = tempDir.resolve("appdata-rollback");
        System.setProperty("myhomelib.dataDir", dataDir.toString());
        Files.createDirectories(dataDir.resolve("config"));
        Path readerFile = dataDir.resolve("config/reader-preferences.json");
        Files.writeString(readerFile, "{\"fontFamily\":\"Old\"}");

        Db target = db(tempDir.resolve("rollback-target.db"));
        createSchema(target.jdbc());
        target.jdbc().update("INSERT INTO books(id,lib_id,rate,progress,review) VALUES('new','ROLL-1',2,0,NULL)");

        FailOnceReplaceSettings settings = new FailOnceReplaceSettings();
        settings.put("filter.global.language", "ru");
        settings.put("filter.global.author", "before");

        Path manifest = tempDir.resolve("rollback-manifest.json");
        Files.writeString(manifest, """
                {
                  "schemaVersion":2,"format":"myhomelib-user-data",
                  "bookState":[{"libId":"ROLL-1","sourceBookId":"new","rate":9,"progress":0,"review":"changed"}],
                  "readingProgress":[],"readingHistory":[],"readingStats":[],"bookmarks":[],
                  "groups":[],"groupMemberships":[],"savedSearches":[],
                  "filterSettings":{"filter.global.language":"uk"},
                  "readerSettings":{"global":{"fontFamily":"New"},"perBook":[]}
                }
                """);

        VersionedUserDataTransferAdapter adapter = new VersionedUserDataTransferAdapter(
                manager(target), settings, new ObjectMapper());

        assertThatThrownBy(() -> adapter.restoreFrom(manifest))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("portable user data");

        assertThat(target.jdbc().queryForObject("SELECT rate FROM books WHERE id='new'", Integer.class)).isEqualTo(2);
        assertThat(settings.get("filter.global.language", "")).isEqualTo("ru");
        assertThat(settings.get("filter.global.author", "")).isEqualTo("before");
        assertThat(Files.readString(readerFile)).contains("Old");
    }

    private static CollectionManager manager(Db db) {
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(db.jdbc());
        when(manager.getCurrentDataSource()).thenReturn(db.dataSource());
        return manager;
    }

    private static Db db(Path file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return new Db(dataSource, new JdbcTemplate(dataSource));
    }

    private static void createSchema(JdbcTemplate j) {
        j.execute("CREATE TABLE books(id TEXT PRIMARY KEY, lib_id TEXT, rate INTEGER DEFAULT 0, progress INTEGER DEFAULT 0, review TEXT)");
        j.execute("CREATE INDEX idx_books_lib_id ON books(lib_id)");
        // Match the current V71/V72 schema, not the pre-media-split legacy fixture.
        j.execute("CREATE TABLE reading_progress(book_id TEXT PRIMARY KEY, paragraph_id TEXT NOT NULL, char_offset INTEGER NOT NULL, percent REAL NOT NULL, updated_at TEXT NOT NULL, anchor_id TEXT, text_anchor_id TEXT, audio_anchor_id TEXT, paragraph_index INTEGER DEFAULT 0, last_device TEXT NOT NULL DEFAULT 'desktop', revision INTEGER NOT NULL DEFAULT 0)");
        j.execute("CREATE TABLE reading_history(book_id TEXT PRIMARY KEY,last_opened_at TEXT NOT NULL,open_count INTEGER NOT NULL DEFAULT 1)");
        j.execute("CREATE TABLE reading_stats(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL UNIQUE,first_read_at TEXT NOT NULL,last_read_at TEXT NOT NULL,total_reading_seconds INTEGER DEFAULT 0,reading_sessions INTEGER DEFAULT 0,start_percent INTEGER DEFAULT 0,end_percent INTEGER DEFAULT 0,current_percent INTEGER DEFAULT 0,completed_at TEXT)");
        j.execute("CREATE TABLE bookmarks(id TEXT PRIMARY KEY,book_id TEXT NOT NULL,paragraph_id TEXT NOT NULL,char_offset INTEGER DEFAULT 0,position REAL DEFAULT 0,chapter_title TEXT,context TEXT,created_at TEXT NOT NULL)");
        j.execute("CREATE TABLE groups(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,allow_delete INTEGER DEFAULT 1)");
        j.execute("CREATE TABLE book_groups(book_id TEXT NOT NULL,group_id INTEGER NOT NULL,PRIMARY KEY(book_id,group_id))");
        j.execute("CREATE TABLE saved_searches(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE,query TEXT NOT NULL,filters TEXT,created_at TEXT NOT NULL,last_used TEXT NOT NULL,use_count INTEGER DEFAULT 0)");
        j.execute("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT)");
        j.execute("CREATE TABLE reader_book_preferences(book_id TEXT PRIMARY KEY,preferences_json TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        j.execute("CREATE TABLE reader_preference_overrides(scope_type TEXT NOT NULL,scope_key TEXT NOT NULL,preferences_json TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(scope_type,scope_key))");
        j.execute("CREATE TABLE reading_lists(id TEXT PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        j.execute("CREATE UNIQUE INDEX ux_reading_lists_normalized_name ON reading_lists(lower(trim(name)))");
        j.execute("CREATE TABLE reading_list_items(list_id TEXT NOT NULL,book_id TEXT NOT NULL,position INTEGER NOT NULL,added_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(list_id,book_id),UNIQUE(list_id,position))");
    }

    private record Db(SQLiteDataSource dataSource, JdbcTemplate jdbc) { }

    private static class MapSettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }

        @Override
        public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }

        @Override
        public void remove(String key) { values.remove(key); }

        @Override
        public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }

    private static final class FailOnceReplaceSettings extends MapSettings {
        private boolean failNextReplace = true;

        @Override
        public void replaceByPrefix(String prefix, Map<String, String> values) {
            super.replaceByPrefix(prefix, values);
            if (failNextReplace) {
                failNextReplace = false;
                throw new IllegalStateException("simulated settings persistence failure");
            }
        }
    }
}
