package com.myhomelibcorp.infrastructure.opds;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class SqliteOpdsCatalogQueryAdapterTest {
    private Connection connection;
    private SqliteOpdsCatalogQueryAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite::memory:");
        connection = sqlite.getConnection();
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        createSchema(jdbc);
        seed(jdbc);

        CollectionManager manager = Mockito.mock(CollectionManager.class);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(manager.getCurrentCollection()).thenReturn(new Collection(
                "collection-1", "Основна", Path.of("."), "library.db", 0,
                null, null, null, null));
        adapter = new SqliteOpdsCatalogQueryAdapter(manager);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void exposesCurrentCollectionAndCountsOnlyLiveBooks() {
        var collection = adapter.currentCollection().orElseThrow();
        assertThat(collection.id()).isEqualTo("collection-1");
        assertThat(collection.label()).isEqualTo("Основна");
        assertThat(collection.bookCount()).isEqualTo(2);
    }

    @Test
    void groupsAndFavoritesAreBoundedAndIgnoreDeletedBooks() {
        var groups = adapter.groups(0, 1);
        assertThat(groups.total()).isEqualTo(2);
        assertThat(groups.items()).hasSize(1);
        assertThat(groups.hasNext()).isTrue();
        assertThat(groups.items().getFirst().label()).isEqualTo("Favorites");
        assertThat(groups.items().getFirst().bookCount()).isEqualTo(1);

        var favorites = adapter.favorites(0, 50);
        assertThat(favorites.total()).isEqualTo(1);
        assertThat(favorites.items()).extracting("id").containsExactly("b1");

        var groupBooks = adapter.groupBooks("2", 0, 50);
        assertThat(groupBooks.items()).extracting("id").containsExactly("b2");
    }

    @Test
    void continueReadingContainsOnlyIncompleteProgressAndUsesRecentOrder() {
        var page = adapter.continueReading(0, 50);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting("id").containsExactly("b2");
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE books (
                    id TEXT PRIMARY KEY, title TEXT, series TEXT, language TEXT, year INTEGER,
                    annotation TEXT, format TEXT, local INTEGER, file_name TEXT, archive_entry TEXT,
                    keywords TEXT, deleted INTEGER NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("CREATE TABLE authors (id TEXT PRIMARY KEY, last_name TEXT, first_name TEXT, middle_name TEXT)");
        jdbc.execute("CREATE TABLE book_authors (book_id TEXT, author_id TEXT)");
        jdbc.execute("CREATE TABLE groups (id INTEGER PRIMARY KEY, name TEXT, allow_delete INTEGER)");
        jdbc.execute("CREATE TABLE book_groups (book_id TEXT, group_id INTEGER)");
        jdbc.execute("CREATE TABLE reading_progress (book_id TEXT PRIMARY KEY, percent REAL, updated_at TEXT)");
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO books VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                "b1", "Alpha", "", "uk", 2026, "", "fb2", 1, "a.fb2", "", "", 0);
        jdbc.update("INSERT INTO books VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                "b2", "Beta", "", "uk", 2026, "", "epub", 1, "b.epub", "", "", 0);
        jdbc.update("INSERT INTO books VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                "b3", "Deleted", "", "uk", 2026, "", "pdf", 1, "c.pdf", "", "", 1);
        jdbc.update("INSERT INTO groups VALUES (1,'Favorites',0)");
        jdbc.update("INSERT INTO groups VALUES (2,'To Read',0)");
        jdbc.update("INSERT INTO book_groups VALUES ('b1',1)");
        jdbc.update("INSERT INTO book_groups VALUES ('b3',1)");
        jdbc.update("INSERT INTO book_groups VALUES ('b2',2)");
        jdbc.update("INSERT INTO reading_progress VALUES ('b1',100.0,'2026-09-12T10:00:00Z')");
        jdbc.update("INSERT INTO reading_progress VALUES ('b2',42.5,'2026-09-12T11:00:00Z')");
    }
}
