package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.infrastructure.persistence.mapper.GroupRowMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SqliteCollectionBookReadAdapterTest {

    @TempDir
    Path temp;

    @Test
    void resolvesUnicodeGroupNamesAndKeepsTheCollectionConnectionReadOnly() throws Exception {
        Path database = createDatabase("groups.sqlite",
                "INSERT INTO groups(id, name, allow_delete) VALUES (7, 'Вибране', 0)");
        SqliteBookQueryRepository bookQueries = mock(SqliteBookQueryRepository.class);
        when(bookQueries.findGroupExportPage(any(JdbcTemplate.class), any(BookQuery.class))).thenAnswer(invocation -> {
            JdbcTemplate jdbc = invocation.getArgument(0);
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO groups(id, name, allow_delete) VALUES (99, 'must-not-write', 1)"))
                    .isInstanceOf(DataAccessException.class);
            return PageResult.of(List.of(), 0, 0, 100);
        });

        SqliteCollectionBookReadAdapter adapter = new SqliteCollectionBookReadAdapter(
                bookQueries, new GroupRowMapper());
        Collection collection = collection(database);

        try (var session = adapter.open(collection)) {
            var byId = session.findGroupById(7L);
            assertThat(byId).isPresent();
            assertThat(byId.orElseThrow().getName()).isEqualTo("Вибране");
            assertThat(byId.orElseThrow().isAllowDelete()).isFalse();

            var byName = session.findGroupByName("  вИБРАНЕ  ");
            assertThat(byName).isPresent();
            assertThat(byName.orElseThrow().getId().asLong()).isEqualTo(7L);

            BookQuery query = BookQuery.builder()
                    .groupId(GroupId.fromLong(7L))
                    .build();
            assertThat(session.findPage(query).content()).isEmpty();
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM groups")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        verify(bookQueries, times(1)).findGroupExportPage(any(JdbcTemplate.class), any(BookQuery.class));
    }

    @Test
    void rejectsAmbiguousCaseInsensitiveGroupNamesInsteadOfChoosingOne() throws Exception {
        Path database = createDatabase("ambiguous.sqlite",
                "INSERT INTO groups(id, name, allow_delete) VALUES (1, 'Favorites', 0)",
                "INSERT INTO groups(id, name, allow_delete) VALUES (2, 'favorites', 1)");
        SqliteCollectionBookReadAdapter adapter = new SqliteCollectionBookReadAdapter(
                mock(SqliteBookQueryRepository.class), new GroupRowMapper());

        try (var session = adapter.open(collection(database))) {
            assertThatThrownBy(() -> session.findGroupByName("FAVORITES"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Ambiguous group name");
        }
    }

    @Test
    void normalizesRepeatedUnicodeWhitespaceAndCanonicalCompositionBeforeMatching() throws Exception {
        Path database = createDatabase("normalized.sqlite",
                "INSERT INTO groups(id, name, allow_delete) VALUES (4, 'Café  книги', 1)");
        SqliteCollectionBookReadAdapter adapter = new SqliteCollectionBookReadAdapter(
                mock(SqliteBookQueryRepository.class), new GroupRowMapper());

        try (var session = adapter.open(collection(database))) {
            // The query uses decomposed é and a non-breaking space; stored text uses
            // composed é and repeated ordinary spaces.
            var match = session.findGroupByName("  CAFE\u0301\u00A0 книги  ");
            assertThat(match).isPresent();
            assertThat(match.orElseThrow().getId().asLong()).isEqualTo(4L);
        }
    }

    @Test
    void rejectsGroupsThatDifferOnlyByRepeatedInternalWhitespace() throws Exception {
        Path database = createDatabase("ambiguous-whitespace.sqlite",
                "INSERT INTO groups(id, name, allow_delete) VALUES (1, 'Science Fiction', 0)",
                "INSERT INTO groups(id, name, allow_delete) VALUES (2, 'Science  Fiction', 1)");
        SqliteCollectionBookReadAdapter adapter = new SqliteCollectionBookReadAdapter(
                mock(SqliteBookQueryRepository.class), new GroupRowMapper());

        try (var session = adapter.open(collection(database))) {
            assertThatThrownBy(() -> session.findGroupByName("SCIENCE Fiction"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Ambiguous group name");
        }
    }

    @Test
    void readSnapshotDetectsExternalCommitWhileRetainingItsReadTransaction() throws Exception {
        Path database = createDatabase("changed.sqlite",
                "INSERT INTO groups(id, name, allow_delete) VALUES (1, 'Snapshot', 1)");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
        }

        SqliteCollectionBookReadAdapter adapter = new SqliteCollectionBookReadAdapter(
                mock(SqliteBookQueryRepository.class), new GroupRowMapper());
        var session = adapter.open(collection(database));
        assertThat(session.findGroupByName("Snapshot")).isPresent();

        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = writer.createStatement()) {
            statement.executeUpdate("UPDATE groups SET allow_delete = 0 WHERE id = 1");
        }

        assertThatThrownBy(session::close)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("changed during portable group export");
    }

    private Path createDatabase(String fileName, String... inserts) throws Exception {
        Path database = temp.resolve(fileName);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE groups (
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        allow_delete INTEGER NOT NULL DEFAULT 1
                    )
                    """);
            for (String insert : inserts) statement.execute(insert);
        }
        return database;
    }

    private Collection collection(Path database) {
        return new Collection("collection-" + database.getFileName(), "Test collection",
                temp, database.toString(), 0, null, null, null, null, null);
    }
}
