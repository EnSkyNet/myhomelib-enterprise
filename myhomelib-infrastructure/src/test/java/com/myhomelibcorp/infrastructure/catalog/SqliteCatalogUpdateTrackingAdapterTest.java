package com.myhomelibcorp.infrastructure.catalog;

import com.myhomelibcorp.application.catalog.CatalogBookSnapshot;
import com.myhomelibcorp.application.catalog.CatalogUpdateType;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.infrastructure.persistence.sqlite.TestCollectionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteCatalogUpdateTrackingAdapterTest {
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqliteCatalogUpdateTrackingAdapter adapter;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:file:stage6-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setMaximumPoolSize(2);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentCollection(new Collection("c1", "Online", Path.of("."), null, 1, null, null, "https://example.test", null));
        manager.setCurrentDataSource(dataSource);
        manager.setCurrentJdbcTemplate(jdbc);
        adapter = new SqliteCatalogUpdateTrackingAdapter(manager);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void repeatSyncDoesNotCreateFalsePositiveAndChangedDownloadedBookCreatesExactlyOneUpdate() {
        insertBook("b1", true);
        var first = adapter.beginSync("remote-collection:c1", "https://user:secret@example.test/catalog.inpx?token=x", "source-a");
        adapter.recordImportedBooks(first, List.of(snapshot("b1", "book-a")));
        assertThat(first.initialBaseline()).isTrue();
        assertThat(adapter.countPendingUpdates()).isZero();

        var same = adapter.beginSync("remote-collection:c1", "https://example.test/catalog.inpx?token=y", "source-a");
        adapter.recordImportedBooks(same, List.of(snapshot("b1", "book-a")));
        assertThat(same.sourceRevision()).isEqualTo(1);
        assertThat(same.sourceChanged()).isFalse();
        assertThat(adapter.countPendingUpdates()).isZero();

        var changed = adapter.beginSync("remote-collection:c1", "https://example.test/catalog.inpx", "source-b");
        adapter.recordImportedBooks(changed, List.of(snapshot("b1", "book-b")));
        assertThat(changed.sourceRevision()).isEqualTo(2);
        assertThat(adapter.countPendingUpdates()).isEqualTo(1);
        assertThat(adapter.findPendingUpdateItems(10, null)).singleElement()
                .extracting(e -> e.type())
                .isEqualTo(CatalogUpdateType.UPDATED_DOWNLOADED_BOOK);

        var repeatedChanged = adapter.beginSync("remote-collection:c1", "https://example.test/catalog.inpx", "source-b");
        adapter.recordImportedBooks(repeatedChanged, List.of(snapshot("b1", "book-b")));
        assertThat(adapter.countPendingUpdates()).isEqualTo(1);
    }

    @Test
    void newBookByFollowedAuthorIsDetectedOnlyAfterInitialBaseline() {
        String authorId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO authors(id, first_name, last_name) VALUES (?, 'Ada', 'Author')", authorId);
        insertBook("old", false);
        jdbc.update("INSERT INTO book_authors(book_id, author_id) VALUES ('old', ?)", authorId);

        var baseline = adapter.beginSync("remote-collection:c1", null, "source-a");
        adapter.recordImportedBooks(baseline, List.of(snapshot("old", "old-a")));
        adapter.setAuthorFollowed(AuthorId.fromString(authorId), true);
        assertThat(adapter.countPendingUpdates()).isZero();

        insertBook("new", false);
        jdbc.update("INSERT INTO book_authors(book_id, author_id) VALUES ('new', ?)", authorId);
        var revision2 = adapter.beginSync("remote-collection:c1", null, "source-b");
        adapter.recordImportedBooks(revision2, List.of(snapshot("old", "old-a"), snapshot("new", "new-a")));

        assertThat(adapter.findPendingUpdateItems(10, null))
                .filteredOn(e -> e.bookId().equals("new"))
                .singleElement()
                .extracting(e -> e.type())
                .isEqualTo(CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR);
    }

    @Test
    void pendingUpdateItemsPreferFollowedCoauthorAndExposeBookMetadataForUi() {
        String followed = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        String bookId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO authors(id, first_name, last_name) VALUES (?, 'Followed', 'Writer')", followed);
        jdbc.update("INSERT INTO authors(id, first_name, last_name) VALUES (?, 'Other', 'Author')", other);

        adapter.beginSync("remote-collection:c1", null, "source-a");
        adapter.setAuthorFollowed(AuthorId.fromString(followed), true);
        insertBook(bookId, false);
        jdbc.update("INSERT INTO book_authors(book_id, author_id) VALUES (?, ?)", bookId, other);
        jdbc.update("INSERT INTO book_authors(book_id, author_id) VALUES (?, ?)", bookId, followed);

        var revision2 = adapter.beginSync("remote-collection:c1", null, "source-b");
        adapter.recordImportedBooks(revision2, List.of(snapshot(bookId, "book-new")));

        assertThat(adapter.findPendingUpdateItems(10, null)).singleElement().satisfies(item -> {
            assertThat(item.bookId()).isEqualTo(bookId);
            assertThat(item.bookTitle()).isEqualTo("Title " + bookId);
            assertThat(item.authorId()).isEqualTo(followed);
            assertThat(item.authorName()).isEqualTo("Writer Followed");
            assertThat(item.type()).isEqualTo(CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR);
        });
    }

    @Test
    void pendingUpdateItemsUseStableKeysetCursor() {
        jdbc.update("INSERT INTO books(id,title,file_name,local) VALUES ('k1','K1','k1.fb2',0)");
        jdbc.update("INSERT INTO books(id,title,file_name,local) VALUES ('k2','K2','k2.fb2',0)");
        jdbc.update("INSERT INTO books(id,title,file_name,local) VALUES ('k3','K3','k3.fb2',0)");
        jdbc.update("INSERT INTO catalog_update_events(book_id,update_type,detected_revision,catalog_fingerprint,detected_at) VALUES ('k1','NEW_BY_FOLLOWED_AUTHOR',1,'a','2026-08-30T10:00:00')");
        jdbc.update("INSERT INTO catalog_update_events(book_id,update_type,detected_revision,catalog_fingerprint,detected_at) VALUES ('k2','NEW_BY_FOLLOWED_AUTHOR',1,'b','2026-08-30T10:00:00')");
        jdbc.update("INSERT INTO catalog_update_events(book_id,update_type,detected_revision,catalog_fingerprint,detected_at) VALUES ('k3','NEW_BY_FOLLOWED_AUTHOR',1,'c','2026-08-29T10:00:00')");

        var first = adapter.findPendingUpdateItems(2, null);
        assertThat(first).extracting(item -> item.bookId()).containsExactly("k1", "k2");
        var second = adapter.findPendingUpdateItems(2, com.myhomelibcorp.application.catalog.CatalogUpdateCursor.after(first.getLast()));
        assertThat(second).extracting(item -> item.bookId()).containsExactly("k3");
    }

    @Test
    void successfulDownloadBaselineAcknowledgesPendingBookEvents() {
        String authorId = UUID.randomUUID().toString();
        String bookId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO authors(id, first_name, last_name) VALUES (?, 'Followed', 'Author')", authorId);

        adapter.beginSync("remote-collection:c1", null, "source-a");
        adapter.setAuthorFollowed(AuthorId.fromString(authorId), true);

        insertBook(bookId, false);
        jdbc.update("INSERT INTO book_authors(book_id, author_id) VALUES (?, ?)", bookId, authorId);
        var revision2 = adapter.beginSync("remote-collection:c1", null, "source-b");
        adapter.recordImportedBooks(revision2, List.of(snapshot(bookId, "book-new")));
        assertThat(adapter.countPendingUpdates()).isEqualTo(1);

        jdbc.update("UPDATE books SET local = 1 WHERE id = ?", bookId);
        adapter.markDownloadedBaseline(com.myhomelibcorp.domain.model.valueobject.BookId.fromString(bookId));

        assertThat(adapter.countPendingUpdates()).isZero();
        var baseline = jdbc.queryForMap(
                "SELECT downloaded_revision, downloaded_fingerprint FROM catalog_book_state WHERE book_id = ?", bookId);
        assertThat(((Number) baseline.get("downloaded_revision")).longValue()).isEqualTo(revision2.sourceRevision());
        assertThat(baseline.get("downloaded_fingerprint")).isEqualTo("book-new");
    }

    @Test
    void missingCatalogRowDoesNotDestroyDownloadedLocalFlag() {
        insertBook("b1", true);
        var baseline = adapter.beginSync("remote-collection:c1", null, "source-a");
        adapter.recordImportedBooks(baseline, List.of(snapshot("b1", "book-a")));

        var next = adapter.beginSync("remote-collection:c1", null, "source-b");
        adapter.markTrackedBooksMissing(next);

        var values = jdbc.queryForMap("SELECT deleted, local FROM books WHERE id = 'b1'");
        assertThat(((Number) values.get("deleted")).intValue()).isEqualTo(1);
        assertThat(((Number) values.get("local")).intValue()).isEqualTo(1);
    }

    private void insertBook(String id, boolean local) {
        jdbc.update("""
                INSERT INTO books(id, title, file_name, folder, archive_entry, file_size, local, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """, id, "Title " + id, id + ".fb2", "old.zip", id + ".fb2", 100L, local ? 1 : 0);
    }

    private CatalogBookSnapshot snapshot(String bookId, String fingerprint) {
        return new CatalogBookSnapshot(bookId, "libid:" + bookId, fingerprint,
                bookId + ".fb2", "catalog.zip", bookId + ".fb2", 120L);
    }
}
