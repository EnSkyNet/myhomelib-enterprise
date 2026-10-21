package com.myhomelibcorp.infrastructure.reader;

import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.reader.ReaderPreferenceScope;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReaderBookPreferencesServiceTest {
    @TempDir Path tempDir;

    @Test
    void missingSettingsTableRecognitionTraversesNestedExceptionCauses() {
        RuntimeException failure = new IllegalStateException("outer",
                new IllegalStateException("[SQLITE_ERROR] no such table: settings"));
        assertThat(ReaderBookPreferencesService.isMissingSettingsTable(failure)).isTrue();
        assertThat(ReaderBookPreferencesService.isMissingSettingsTable(
                new IllegalStateException("no such table: books"))).isFalse();
    }

    @Test
    void missingSettingsTableRecognitionStopsOnUnrelatedCauseCycle() {
        RuntimeException first = new IllegalStateException("reader startup");
        RuntimeException second = new IllegalStateException("unrelated failure");
        first.initCause(second);
        second.initCause(first);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                () -> assertThat(ReaderBookPreferencesService.isMissingSettingsTable(first)).isFalse());
    }

    @Test
    void missingSettingsTableRecognitionFindsMessageInsideCycle() {
        RuntimeException first = new IllegalStateException("outer");
        RuntimeException second = new IllegalStateException("NO SUCH TABLE: settings");
        RuntimeException third = new IllegalStateException("nested");
        first.initCause(second);
        second.initCause(third);
        third.initCause(first);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),
                () -> assertThat(ReaderBookPreferencesService.isMissingSettingsTable(first)).isTrue());
    }

    @Test
    void missingSettingsTableRecognitionHandlesNull() {
        assertThat(ReaderBookPreferencesService.isMissingSettingsTable(null)).isFalse();
    }

    @AfterEach
    void clean() {
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void migratesLegacyBookOverrideIntoGenericBookScopeAndMirrorsBookCrud() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("appdata").toString());
        Path config = tempDir.resolve("appdata/config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("reader-book-preferences.json"), """
                {
                  "11111111-1111-1111-1111-111111111111":{"fontFamily":"Mono"},
                  "22222222-2222-2222-2222-222222222222":{"fontFamily":"Other"}
                }
                """);

        JdbcTemplate jdbc = jdbc(tempDir.resolve("reader.db"));
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id) VALUES(?)", "11111111-1111-1111-1111-111111111111");
        ReaderBookPreferencesService service = service(jdbc);

        ReaderPreferences migrated = service.load("11111111-1111-1111-1111-111111111111").orElseThrow();
        assertThat(migrated.getFontFamily()).isEqualTo("Mono");
        assertThat(migrated.isShowStatusBar()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_book_preferences", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_preference_overrides WHERE scope_type='BOOK'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT value FROM settings WHERE key='v71_reader_book_preferences_json_migrated'", String.class)).isEqualTo("1");

        service.delete("11111111-1111-1111-1111-111111111111");
        assertThat(service.load("11111111-1111-1111-1111-111111111111")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_book_preferences", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_preference_overrides WHERE scope_type='BOOK'", Integer.class)).isZero();
        assertThat(Files.readString(config.resolve("reader-book-preferences.json"))).contains("22222222-2222-2222-2222-222222222222");
    }

    @Test
    void storesSeriesAndFormatIndependentlyAndMalformedScopeFailsSafe() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("appdata-scoped").toString());
        JdbcTemplate jdbc = jdbc(tempDir.resolve("scoped.db"));
        createSchema(jdbc);
        ReaderBookPreferencesService service = service(jdbc);

        service.save(ReaderPreferenceScope.series(" Series A "), ReaderPreferences.builder().theme("series").build());
        service.save(ReaderPreferenceScope.format("EPUB"), ReaderPreferences.builder().theme("format").build());
        assertThat(service.load(ReaderPreferenceScope.series("series a")).orElseThrow().getTheme()).isEqualTo("series");
        assertThat(service.load(ReaderPreferenceScope.format("epub")).orElseThrow().getTheme()).isEqualTo("format");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_book_preferences", Integer.class)).isZero();

        jdbc.update("UPDATE reader_preference_overrides SET preferences_json='not-json' WHERE scope_type='SERIES'");
        assertThat(service.load(ReaderPreferenceScope.series("series a"))).isEmpty();
        assertThat(service.load(ReaderPreferenceScope.format("epub"))).isPresent();
    }

    @Test
    void switchingActiveCollectionKeepsScopedProfilesIsolated() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("appdata-switch").toString());
        JdbcTemplate first = jdbc(tempDir.resolve("first.db"));
        JdbcTemplate second = jdbc(tempDir.resolve("second.db"));
        createSchema(first);
        createSchema(second);
        AtomicReference<JdbcTemplate> active = new AtomicReference<>(first);
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenAnswer(ignored -> active.get());
        ObjectMapper mapper = new ObjectMapper();
        ReaderBookPreferencesService service = new ReaderBookPreferencesService(manager, mapper, new ReaderPreferencesJsonCodec(mapper));

        service.save(ReaderPreferenceScope.series("Series A"), ReaderPreferences.builder().theme("first").build());
        active.set(second);
        assertThat(service.load(ReaderPreferenceScope.series("Series A"))).isEmpty();
        service.save(ReaderPreferenceScope.series("Series A"), ReaderPreferences.builder().theme("second").build());
        active.set(first);
        assertThat(service.load(ReaderPreferenceScope.series("Series A")).orElseThrow().getTheme()).isEqualTo("first");
    }

    @Test
    void legacyMigrationRefusesSymlinkedPreferencesSource() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("appdata-symlink").toString());
        Path config = tempDir.resolve("appdata-symlink/config");
        Files.createDirectories(config);
        Path outside = tempDir.resolve("outside-reader-book-preferences.json");
        Files.writeString(outside, """
                {"11111111-1111-1111-1111-111111111111":{"fontFamily":"Leaked"}}
                """, StandardCharsets.UTF_8);
        Path legacy = config.resolve("reader-book-preferences.json");
        Assumptions.assumeTrue(canCreateSymlink(legacy, outside));

        JdbcTemplate jdbc = jdbc(tempDir.resolve("reader-symlink.db"));
        createSchema(jdbc);
        jdbc.update("INSERT INTO books(id) VALUES(?)", "11111111-1111-1111-1111-111111111111");
        ReaderBookPreferencesService service = service(jdbc);

        assertThat(service.load("11111111-1111-1111-1111-111111111111")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_book_preferences", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_preference_overrides", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settings WHERE key='v71_reader_book_preferences_json_migrated'", Integer.class)).isZero();
        assertThat(Files.readString(outside, StandardCharsets.UTF_8)).contains("Leaked");
    }

    private ReaderBookPreferencesService service(JdbcTemplate jdbc) {
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        ObjectMapper mapper = new ObjectMapper();
        return new ReaderBookPreferencesService(manager, mapper, new ReaderPreferencesJsonCodec(mapper));
    }

    private static JdbcTemplate jdbc(Path db) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        return new JdbcTemplate(ds);
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE books(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT)");
        jdbc.execute("CREATE TABLE reader_book_preferences(book_id TEXT PRIMARY KEY,preferences_json TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE reader_preference_overrides(scope_type TEXT NOT NULL,scope_key TEXT NOT NULL,preferences_json TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(scope_type,scope_key))");
    }

    private static boolean canCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }
}
