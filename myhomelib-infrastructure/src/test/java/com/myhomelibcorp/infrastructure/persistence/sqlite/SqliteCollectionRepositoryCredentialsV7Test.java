package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteCollectionRepositoryCredentialsV7Test {
    @TempDir Path temp;
    private JdbcTemplate jdbc;
    private SqliteCollectionRepository repository;

    @BeforeAll
    static void configureStableTestKey() {
        System.setProperty("myhomelib.encryption.key", Base64.getEncoder().encodeToString(new byte[32]));
    }

    @BeforeEach
    void setUp() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("meta.db"));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE collections(
                    id TEXT PRIMARY KEY,name TEXT NOT NULL,root_folder TEXT,db_file TEXT,type INTEGER DEFAULT 0,
                    user TEXT,password TEXT,url TEXT,notes TEXT,connection_script TEXT,created TEXT)
                """);
        jdbc.execute("CREATE TABLE collection_books(collection_id TEXT,book_id TEXT,PRIMARY KEY(collection_id,book_id))");
        repository = new SqliteCollectionRepository(jdbc);
    }

    @Test
    void saveEncryptsOnceAndReturnedObjectMatchesPersistence() {
        Collection input = new Collection("c1", "Remote", temp, temp.resolve("library.db").toString(), 2,
                "user", "secret-password", "https://example.invalid", "notes");

        Collection saved = repository.save(input);
        String stored = jdbc.queryForObject("SELECT password FROM collections WHERE id='c1'", String.class);

        assertThat(stored).isNotEqualTo("secret-password");
        assertThat(saved.getPassword()).isEqualTo(stored);
        assertThat(saved.isPasswordEncrypted()).isTrue();
        assertThat(saved.getDecryptedPassword()).isEqualTo("secret-password");

        Collection savedAgain = repository.save(saved);
        String storedAgain = jdbc.queryForObject("SELECT password FROM collections WHERE id='c1'", String.class);
        assertThat(storedAgain).isEqualTo(stored);
        assertThat(savedAgain.getPassword()).isEqualTo(stored);
    }

    @Test
    void readingLegacyPlaintextMigratesItWithoutChangingMetadata() {
        jdbc.update("""
                INSERT INTO collections(id,name,root_folder,db_file,type,user,password,url,notes)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, "legacy", "Legacy", temp.toString(), temp.resolve("legacy.db").toString(), 7,
                "login", "legacy-secret", "https://example.invalid/legacy", "keep-notes");

        Collection loaded = repository.findById("legacy").orElseThrow();
        String stored = jdbc.queryForObject("SELECT password FROM collections WHERE id='legacy'", String.class);

        assertThat(stored).isNotEqualTo("legacy-secret");
        assertThat(loaded.getPassword()).isEqualTo(stored);
        assertThat(loaded.getDecryptedPassword()).isEqualTo("legacy-secret");
        assertThat(loaded.getType()).isEqualTo(7);
        assertThat(loaded.getUrl()).isEqualTo("https://example.invalid/legacy");
        assertThat(loaded.getNotes()).isEqualTo("keep-notes");
        assertThat(loaded.getDbFile()).endsWith("legacy.db");
    }
}
