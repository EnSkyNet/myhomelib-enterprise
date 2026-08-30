package com.myhomelibcorp.infrastructure.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionedUserDataTransferAdapterTest {
    @TempDir Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clean() {
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void portableV2RoundTripMapsBookScopedUserDataByLibId() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("appdata").toString());
        Files.createDirectories(tempDir.resolve("appdata/config"));

        Db source = db(tempDir.resolve("source.db"));
        createSchema(source.jdbc());
        source.jdbc().update("INSERT INTO books(id,lib_id,rate,progress,review) VALUES('old-1','L100',8,42,'great')");
        source.jdbc().update("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,percent,updated_at,anchor_id,paragraph_index) VALUES('old-1','p1',12,42.5,'2026-08-25T10:00:00Z','a1',3)");
        source.jdbc().update("INSERT INTO reading_history(book_id,last_opened_at,open_count) VALUES('old-1','2026-08-25T11:00:00Z',4)");
        source.jdbc().update("INSERT INTO reading_stats(book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,start_percent,end_percent,current_percent,completed_at) VALUES('old-1','a','b',120,2,0,42,42,NULL)");
        source.jdbc().update("INSERT INTO bookmarks(id,book_id,paragraph_id,char_offset,position,chapter_title,context,created_at) VALUES('bm1','old-1','p1',9,0.4,'Ch','ctx','2026-08-25T12:00:00Z')");
        source.jdbc().update("INSERT INTO groups(name,allow_delete) VALUES('Favorites',0)");
        source.jdbc().update("INSERT INTO book_groups(book_id,group_id) SELECT 'old-1',id FROM groups WHERE name='Favorites'");
        source.jdbc().update("INSERT INTO saved_searches(id,name,query,filters,created_at,last_used,use_count) VALUES('s1','SciFi','space','{}','a','b',5)");

        MapSettings sourceSettings = new MapSettings();
        sourceSettings.put("filter.global.language", "uk");
        Files.writeString(tempDir.resolve("appdata/config/reader-preferences.json"), "{\"fontFamily\":\"Serif\"}");
        source.jdbc().update("INSERT INTO reader_book_preferences(book_id,preferences_json) VALUES(?,?)", "old-1", "{\"fontFamily\":\"Mono\"}");

        Path exported = tempDir.resolve("user-data.json");
        VersionedUserDataTransferAdapter sourceAdapter = new VersionedUserDataTransferAdapter(manager(source), sourceSettings, mapper);
        var exportResult = sourceAdapter.exportTo(exported);
        assertThat(exportResult.schemaVersion()).isEqualTo(2);
        assertThat(Files.readString(exported)).contains("\"libId\" : \"L100\"").contains("\"schemaVersion\" : 2");

        // Simulate a freshly imported catalogue: the internal UUID/id changed, LibID did not.
        Db target = db(tempDir.resolve("target.db"));
        createSchema(target.jdbc());
        target.jdbc().update("INSERT INTO books(id,lib_id,rate,progress,review) VALUES('new-77','L100',0,0,NULL)");
        MapSettings targetSettings = new MapSettings();
        Files.deleteIfExists(tempDir.resolve("appdata/config/reader-preferences.json"));

        VersionedUserDataTransferAdapter targetAdapter = new VersionedUserDataTransferAdapter(manager(target), targetSettings, mapper);
        var result = targetAdapter.restoreFrom(exported);

        assertThat(result.sourceSchemaVersion()).isEqualTo(2);
        assertThat(result.unmatchedBooks()).isZero();
        assertThat(target.jdbc().queryForMap("SELECT rate,progress,review FROM books WHERE id='new-77'"))
                .containsEntry("rate", 8).containsEntry("progress", 42).containsEntry("review", "great");
        assertThat(target.jdbc().queryForObject("SELECT book_id FROM bookmarks WHERE id='bm1'", String.class)).isEqualTo("new-77");
        assertThat(target.jdbc().queryForObject("SELECT open_count FROM reading_history WHERE book_id='new-77'", Integer.class)).isEqualTo(4);
        assertThat(target.jdbc().queryForObject("SELECT COUNT(*) FROM book_groups bg JOIN groups g ON g.id=bg.group_id WHERE bg.book_id='new-77' AND g.name='Favorites'", Integer.class)).isEqualTo(1);
        assertThat(target.jdbc().queryForObject("SELECT query FROM saved_searches WHERE name='SciFi'", String.class)).isEqualTo("space");
        assertThat(targetSettings.get("filter.global.language", "")).isEqualTo("uk");
        assertThat(Files.readString(tempDir.resolve("appdata/config/reader-preferences.json"))).contains("Serif");
        String perBook = target.jdbc().queryForObject(
                "SELECT preferences_json FROM reader_book_preferences WHERE book_id='new-77'", String.class);
        assertThat(perBook).contains("Mono");

        // Repeated restore is idempotent for singleton/membership/bookmark data.
        targetAdapter.restoreFrom(exported);
        assertThat(target.jdbc().queryForObject("SELECT COUNT(*) FROM bookmarks WHERE id='bm1'", Integer.class)).isEqualTo(1);
        assertThat(target.jdbc().queryForObject("SELECT COUNT(*) FROM book_groups WHERE book_id='new-77'", Integer.class)).isEqualTo(1);
    }

    @Test
    void sequentiallyMigratesPreviousV1Manifest() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("appdata-v1").toString());
        Db target = db(tempDir.resolve("target-v1.db"));
        createSchema(target.jdbc());
        target.jdbc().update("INSERT INTO books(id,lib_id,rate,progress,review) VALUES('new','LEGACY-9',0,0,NULL)");
        Path file = tempDir.resolve("v1.json");
        Files.writeString(file, """
                {
                  "version": 1,
                  "ratings": [{"libId":"LEGACY-9","rate":7,"progress":13,"review":"v1"}],
                  "reading": [{"libId":"LEGACY-9","paragraphId":"p","charOffset":2,"percent":13.0,"updatedAt":"2026-01-01T00:00:00Z"}]
                }
                """);
        VersionedUserDataTransferAdapter adapter = new VersionedUserDataTransferAdapter(manager(target), new MapSettings(), mapper);
        var result = adapter.restoreFrom(file);
        assertThat(result.sourceSchemaVersion()).isEqualTo(1);
        assertThat(result.effectiveSchemaVersion()).isEqualTo(2);
        assertThat(target.jdbc().queryForObject("SELECT rate FROM books WHERE id='new'", Integer.class)).isEqualTo(7);
        assertThat(target.jdbc().queryForObject("SELECT paragraph_id FROM reading_progress WHERE book_id='new'", String.class)).isEqualTo("p");
    }

    @Test
    void ambiguousLibIdIsSkippedUnlessExportedInternalIdStillMatches() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("appdata-ambiguous").toString());
        Db target = db(tempDir.resolve("target-ambiguous.db"));
        createSchema(target.jdbc());
        target.jdbc().update("INSERT INTO books(id,lib_id,rate,progress,review) VALUES('11111111-1111-1111-1111-111111111111','DUP',0,0,NULL)");
        target.jdbc().update("INSERT INTO books(id,lib_id,rate,progress,review) VALUES('22222222-2222-2222-2222-222222222222','DUP',0,0,NULL)");

        Path ambiguous = tempDir.resolve("ambiguous.json");
        Files.writeString(ambiguous, portableWithBookState(
                "{\"libId\":\"DUP\",\"sourceBookId\":\"old-id\",\"rate\":9,\"progress\":0,\"review\":\"x\"}"));
        VersionedUserDataTransferAdapter adapter = new VersionedUserDataTransferAdapter(manager(target), new MapSettings(), mapper);
        var skipped = adapter.restoreFrom(ambiguous);
        assertThat(skipped.unmatchedBooks()).isGreaterThan(0);
        assertThat(target.jdbc().queryForObject("SELECT SUM(rate) FROM books WHERE lib_id='DUP'", Integer.class)).isZero();

        Path exact = tempDir.resolve("ambiguous-exact.json");
        Files.writeString(exact, portableWithBookState(
                "{\"libId\":\"DUP\",\"sourceBookId\":\"22222222-2222-2222-2222-222222222222\",\"rate\":7,\"progress\":0,\"review\":\"ok\"}"));
        adapter.restoreFrom(exact);
        assertThat(target.jdbc().queryForObject(
                "SELECT rate FROM books WHERE id='22222222-2222-2222-2222-222222222222'", Integer.class)).isEqualTo(7);
        assertThat(target.jdbc().queryForObject(
                "SELECT rate FROM books WHERE id='11111111-1111-1111-1111-111111111111'", Integer.class)).isZero();
    }

    private static String portableWithBookState(String row) {
        return """
                {
                  "schemaVersion":2,"format":"myhomelib-user-data",
                  "bookState":[%s],"readingProgress":[],"readingHistory":[],"readingStats":[],
                  "bookmarks":[],"groups":[],"groupMemberships":[],"savedSearches":[],
                  "filterSettings":{},"readerSettings":{"global":null,"perBook":[]}
                }
                """.formatted(row);
    }

    private static CollectionManager manager(Db db) {
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(db.jdbc());
        when(manager.getCurrentDataSource()).thenReturn(db.dataSource());
        return manager;
    }

    private static Db db(Path file) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return new Db(ds, new JdbcTemplate(ds));
    }

    private static void createSchema(JdbcTemplate j) {
        j.execute("CREATE TABLE books(id TEXT PRIMARY KEY, lib_id TEXT, rate INTEGER DEFAULT 0, progress INTEGER DEFAULT 0, review TEXT)");
        j.execute("CREATE INDEX idx_books_lib_id ON books(lib_id)");
        j.execute("CREATE TABLE reading_progress(book_id TEXT PRIMARY KEY, paragraph_id TEXT NOT NULL, char_offset INTEGER NOT NULL, percent REAL NOT NULL, updated_at TEXT NOT NULL, anchor_id TEXT, paragraph_index INTEGER DEFAULT 0)");
        j.execute("CREATE TABLE reading_history(book_id TEXT PRIMARY KEY,last_opened_at TEXT NOT NULL,open_count INTEGER NOT NULL DEFAULT 1)");
        j.execute("CREATE TABLE reading_stats(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL UNIQUE,first_read_at TEXT NOT NULL,last_read_at TEXT NOT NULL,total_reading_seconds INTEGER DEFAULT 0,reading_sessions INTEGER DEFAULT 0,start_percent INTEGER DEFAULT 0,end_percent INTEGER DEFAULT 0,current_percent INTEGER DEFAULT 0,completed_at TEXT)");
        j.execute("CREATE TABLE bookmarks(id TEXT PRIMARY KEY,book_id TEXT NOT NULL,paragraph_id TEXT NOT NULL,char_offset INTEGER DEFAULT 0,position REAL DEFAULT 0,chapter_title TEXT,context TEXT,created_at TEXT NOT NULL)");
        j.execute("CREATE TABLE groups(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,allow_delete INTEGER DEFAULT 1)");
        j.execute("CREATE TABLE book_groups(book_id TEXT NOT NULL,group_id INTEGER NOT NULL,PRIMARY KEY(book_id,group_id))");
        j.execute("CREATE TABLE saved_searches(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE,query TEXT NOT NULL,filters TEXT,created_at TEXT NOT NULL,last_used TEXT NOT NULL,use_count INTEGER DEFAULT 0)");
        j.execute("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT)");
        j.execute("CREATE TABLE reader_book_preferences(book_id TEXT PRIMARY KEY,preferences_json TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }

    private record Db(SQLiteDataSource dataSource, JdbcTemplate jdbc) { }

    private static final class MapSettings implements ApplicationSettingsPort {
        private final Map<String,String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if(value==null)values.remove(key); else values.put(key,value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String,String> out = new LinkedHashMap<>();
            values.forEach((k, v) -> { if (k.startsWith(prefix)) out.put(k, v); });
            return out;
        }
    }
}