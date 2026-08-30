package com.myhomelibcorp.infrastructure.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReaderBookPreferencesServiceTest {
    @TempDir Path tempDir;

    @AfterEach
    void clean() {
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void migratesOnlyBooksOfActiveCollectionAndDoesNotResurrectDeletedOverride() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("appdata").toString());
        Path config = tempDir.resolve("appdata/config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reader-book-preferences.json"), """
                {
                  "11111111-1111-1111-1111-111111111111":{"fontFamily":"Mono"},
                  "22222222-2222-2222-2222-222222222222":{"fontFamily":"Other"}
                }
                """);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("reader.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE books(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT)");
        jdbc.execute("CREATE TABLE reader_book_preferences(book_id TEXT PRIMARY KEY,preferences_json TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO books(id) VALUES(?)", "11111111-1111-1111-1111-111111111111");

        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        ObjectMapper mapper = new ObjectMapper();
        ReaderPreferencesJsonCodec codec = new ReaderPreferencesJsonCodec(mapper);
        ReaderBookPreferencesService service = new ReaderBookPreferencesService(manager, mapper, codec);

        ReaderPreferences migrated = service.load("11111111-1111-1111-1111-111111111111").orElseThrow();
        assertThat(migrated.getFontFamily()).isEqualTo("Mono");
        // Missing legacy booleans/maps come from current domain defaults, not Java primitive zero-values.
        assertThat(migrated.isShowStatusBar()).isTrue();
        assertThat(migrated.isAutoTwoPageLandscape()).isTrue();
        assertThat(migrated.isPinchZoom()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_book_preferences", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value FROM settings WHERE key='v71_reader_book_preferences_json_migrated'", String.class)).isEqualTo("1");

        service.delete("11111111-1111-1111-1111-111111111111");
        assertThat(service.load("11111111-1111-1111-1111-111111111111")).isEmpty();
        // Legacy file is deliberately retained as a migration source for other collections.
        assertThat(Files.readString(config.resolve("reader-book-preferences.json"))).contains("22222222-2222-2222-2222-222222222222");
    }
}
