package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.organizer.analysis.AuthorFolderOrderPreview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteAuthorFolderOrderPreviewTest {
    @TempDir Path temp;
    Path database;
    Path root;

    @BeforeEach
    void prepare() throws Exception {
        database = temp.resolve("flib.db");
        root = Files.createDirectory(temp.resolve("library"));
        Files.createDirectory(root.resolve("Алёна Васильева"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE books(id TEXT,folder TEXT,collection_root TEXT,local INTEGER,deleted INTEGER)");
                statement.execute("CREATE TABLE book_authors(book_id TEXT,author_id TEXT)");
                statement.execute("CREATE TABLE authors(id TEXT,first_name TEXT,middle_name TEXT,last_name TEXT)");
            }
            try (var query = connection.prepareStatement("INSERT INTO books VALUES (?,?,?,1,0)")) {
                query.setString(1,"book1"); query.setString(2,"Алёна Васильева");
                query.setString(3,root.toAbsolutePath().toString()); query.executeUpdate();
            }
            connection.createStatement().execute("INSERT INTO authors VALUES ('a','Алёна',NULL,'Васильева')");
            connection.createStatement().execute("INSERT INTO book_authors VALUES ('book1','a')");
        }
    }

    @Test
    void proposesSurnameFirstForStructuredAuthorWithoutWritingToDb() throws Exception {
        var result = new SqliteAuthorFolderOrderPreview().inspect(database, root, "Алёна Васильева");
        assertThat(result.status()).isEqualTo(AuthorFolderOrderPreview.Status.PROPOSAL);
        assertThat(result.proposedName()).isEqualTo("Васильева Алёна");
        assertThat(result.matchingBooks()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("Алёна Васильева"))).isTrue();
        assertThat(Files.exists(root.resolve("Васильева Алёна"))).isFalse();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertThat(connection.createStatement().executeQuery("SELECT folder FROM books").getString(1))
                    .isEqualTo("Алёна Васильева");
        }
    }

    @Test
    void refusesToSuggestWhenAnotherBookLacksStructuredAuthor() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("INSERT INTO books VALUES ('book2','Алёна Васильева','"
                    + root.toAbsolutePath().toString().replace("'", "''") + "',1,0)");
        }
        var result = new SqliteAuthorFolderOrderPreview().inspect(database, root, "Алёна Васильева");
        assertThat(result.status()).isEqualTo(AuthorFolderOrderPreview.Status.AMBIGUOUS);
    }

    @Test
    void detectsFolderCollision() throws Exception {
        Files.createDirectory(root.resolve("Васильева Алёна"));
        assertThat(new SqliteAuthorFolderOrderPreview().inspect(database, root, "Алёна Васильева").status())
                .isEqualTo(AuthorFolderOrderPreview.Status.TARGET_EXISTS);
    }

    @Test
    void considersExistingSurnameFirstCorrect() throws Exception {
        Files.move(root.resolve("Алёна Васильева"), root.resolve("Васильева Алёна"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("UPDATE books SET folder='Васильева Алёна'");
        }
        assertThat(new SqliteAuthorFolderOrderPreview().inspect(database, root, "Васильева Алёна").status())
                .isEqualTo(AuthorFolderOrderPreview.Status.ALREADY_CORRECT);
    }

    @Test
    void batchReportsAllDirectoryStatusesWithSingleCatalogPass() throws Exception {
        Files.createDirectory(root.resolve("Невідомий автор"));
        Files.createDirectory(root.resolve("Васильева Алёна"));
        Files.createDirectory(root.resolve("Борис Климов"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("INSERT INTO books VALUES ('book2','Борис Климов','"
                    + root.toAbsolutePath().toString().replace("'", "''") + "',1,0)");
            connection.createStatement().execute("INSERT INTO authors VALUES ('b','Борис',NULL,'Климов')");
            connection.createStatement().execute("INSERT INTO book_authors VALUES ('book2','b')");
        }
        var rows = new SqliteAuthorFolderOrderPreview().inspectAll(database, root);
        assertThat(rows).hasSize(4);
        assertThat(rows).anySatisfy(row -> {
            if (row.currentName().equals("Алёна Васильева")) {
                assertThat(row.proposedName()).isEqualTo("Васильева Алёна");
                assertThat(row.status()).isEqualTo(AuthorFolderOrderPreview.Status.TARGET_EXISTS);
            }
        });
        assertThat(rows.stream().filter(row -> row.currentName().equals("Борис Климов")).findFirst().orElseThrow()
                .status()).isEqualTo(AuthorFolderOrderPreview.Status.PROPOSAL);
        assertThat(rows.stream().filter(row -> row.currentName().equals("Невідомий автор")).findFirst().orElseThrow()
                .status()).isEqualTo(AuthorFolderOrderPreview.Status.NO_BOOKS);
        assertThat(Files.exists(root.resolve("Алёна Васильева"))).isTrue();
    }

    @Test
    void batchCountsMultiAuthorBookOnceAndRefusesConflictingNames() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("INSERT INTO authors VALUES ('c','Наталя',NULL,'Гончар')");
            connection.createStatement().execute("INSERT INTO book_authors VALUES ('book1','c')");
        }
        var rows = new SqliteAuthorFolderOrderPreview().inspectAll(database, root);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().matchingBooks()).isEqualTo(1);
        assertThat(rows.getFirst().status()).isEqualTo(AuthorFolderOrderPreview.Status.AMBIGUOUS);
    }

    @Test
    void batchIncludesNestedCatalogBooksAndDoesNotCountSymlinkedFolder() throws Exception {
        Files.createDirectories(root.resolve("Алёна Васильева").resolve("Серия"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("INSERT INTO books VALUES ('book2','Алёна Васильева/Серия','"
                    + root.toAbsolutePath().toString().replace("'", "''") + "',1,0)");
            connection.createStatement().execute("INSERT INTO book_authors VALUES ('book2','a')");
        }
        var rows = new SqliteAuthorFolderOrderPreview().inspectAll(database, root);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().matchingBooks()).isEqualTo(2);
        assertThat(rows.getFirst().status()).isEqualTo(AuthorFolderOrderPreview.Status.PROPOSAL);
    }

    @Test
    void batchNeverSuggestsCaseOnlyRenameOnWindows() throws Exception {
        Files.move(root.resolve("Алёна Васильева"), root.resolve("ВАСИЛЬЕВА АЛЁНА"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("UPDATE books SET folder='ВАСИЛЬЕВА АЛЁНА'");
        }
        var rows = new SqliteAuthorFolderOrderPreview().inspectAll(database, root);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().status()).isEqualTo(AuthorFolderOrderPreview.Status.TARGET_EXISTS);
    }

    @Test
    void usesEscapedSqlLikeForNamesContainingUnderscore() throws Exception {
        Files.move(root.resolve("Алёна Васильева"), root.resolve("Алёна_Васильева"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("UPDATE books SET folder='Алёна_Васильева'");
        }
        var result = new SqliteAuthorFolderOrderPreview().inspect(database, root, "Алёна_Васильева");
        assertThat(result.matchingBooks()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(AuthorFolderOrderPreview.Status.PROPOSAL);
    }
}
