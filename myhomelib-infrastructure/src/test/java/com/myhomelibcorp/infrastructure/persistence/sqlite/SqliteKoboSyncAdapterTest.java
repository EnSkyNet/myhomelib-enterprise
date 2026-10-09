package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.kobo.*;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.shared.security.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteKoboSyncAdapterTest {
    @TempDir Path temp;
    private DriverManagerDataSource dataSource;
    private MemorySettings settings;
    private MemorySecretStore secrets;
    private SqliteKoboSyncAdapter adapter;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("kobo.db").toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V66__kobo_sync_state.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CollectionManager collections = mock(CollectionManager.class);
        when(collections.getCurrentDataSource()).thenReturn(dataSource);
        when(collections.getCurrentJdbcTemplate()).thenReturn(jdbc);
        settings = new MemorySettings();
        secrets = new MemorySecretStore();
        adapter = new SqliteKoboSyncAdapter(collections, settings, Optional.of(secrets));
    }

    @Test
    void credentialLivesOnlyInSecretStoreAndRevokeInvalidatesIt() {
        KoboCredentialIssue issued = adapter.enableOrRotate();

        assertThat(issued.token()).isNotBlank();
        assertThat(adapter.authenticate(issued.token())).isTrue();
        assertThat(settings.values.values()).doesNotContain(issued.token());
        assertThat(secrets.values.values()).contains(issued.token());

        adapter.revoke();
        assertThat(adapter.authenticate(issued.token())).isFalse();
        assertThat(secrets.values).isEmpty();
    }

    @Test
    void incrementalAddUnchangedUpdateAndRemoveAreDeterministic() {
        adapter.enableOrRotate();
        KoboBookProjection first = book("book-1", "r1");

        KoboSyncBatch add = adapter.reconcileAndLoad(Stream.of(first), KoboSyncCursor.initial(), 100);
        assertThat(add.changes()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(KoboLibraryChange.Kind.ADD);
            assertThat(c.cursor()).isEqualTo(1);
        });

        KoboSyncBatch unchanged = adapter.reconcileAndLoad(Stream.of(first), add.nextCursor(), 100);
        assertThat(unchanged.changes()).isEmpty();
        assertThat(unchanged.nextCursor()).isEqualTo(add.nextCursor());

        KoboBookProjection changed = book("book-1", "r2");
        KoboSyncBatch update = adapter.reconcileAndLoad(Stream.of(changed), add.nextCursor(), 100);
        assertThat(update.changes()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(KoboLibraryChange.Kind.UPDATE);
            assertThat(c.cursor()).isEqualTo(2);
        });

        KoboSyncBatch remove = adapter.reconcileAndLoad(Stream.empty(), update.nextCursor(), 100);
        assertThat(remove.changes()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(KoboLibraryChange.Kind.REMOVE);
            assertThat(c.bookId()).isEqualTo("book-1");
            assertThat(c.cursor()).isEqualTo(3);
        });
    }

    @Test
    void oldCursorStillSeesOriginalAddAfterUnchangedReconciliation() {
        adapter.enableOrRotate();
        KoboBookProjection book = book("book-1", "r1");
        adapter.reconcileAndLoad(Stream.of(book), KoboSyncCursor.initial(), 100);
        adapter.reconcileAndLoad(Stream.of(book), new KoboSyncCursor(1), 100);

        KoboSyncBatch replay = adapter.reconcileAndLoad(Stream.of(book), KoboSyncCursor.initial(), 100);
        assertThat(replay.changes()).singleElement()
                .extracting(KoboLibraryChange::kind).isEqualTo(KoboLibraryChange.Kind.ADD);
    }

    @Test
    void paginationAndProgressVersionsAreMonotonic() {
        adapter.enableOrRotate();
        KoboSyncBatch first = adapter.reconcileAndLoad(Stream.of(book("a", "r1"), book("b", "r1")),
                KoboSyncCursor.initial(), 1);
        assertThat(first.changes()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        KoboSyncBatch second = adapter.reconcileAndLoad(Stream.of(book("a", "r1"), book("b", "r1")),
                first.nextCursor(), 1);
        assertThat(second.changes()).hasSize(1);
        assertThat(second.hasMore()).isFalse();

        assertThat(adapter.observeProgressVersion("a", "p1")).isEqualTo(1);
        assertThat(adapter.observeProgressVersion("a", "p1")).isEqualTo(1);
        assertThat(adapter.observeProgressVersion("a", "p2")).isEqualTo(2);
    }

    private static KoboBookProjection book(String id, String revision) {
        return new KoboBookProjection(id, revision, "Title " + id, "Author", "", null,
                "artifact-" + id, "fp-" + revision, "epub", id + ".epub", Instant.EPOCH);
    }

    private static final class MemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new HashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String,String> out = new HashMap<>();
            values.forEach((k,v) -> { if (k.startsWith(prefix)) out.put(k,v); });
            return out;
        }
    }

    private static final class MemorySecretStore implements SecretStore {
        private final Map<String, String> values = new HashMap<>();
        @Override public Optional<String> read(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void write(String key, String secret) { values.put(key, secret); }
        @Override public void delete(String key) { values.remove(key); }
        @Override public String backendId() { return "test-secure-store"; }
    }
}
