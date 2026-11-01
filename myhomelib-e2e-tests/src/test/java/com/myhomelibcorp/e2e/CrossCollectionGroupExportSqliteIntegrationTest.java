package com.myhomelibcorp.e2e;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.CollectionBookReadPort;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookListRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.GenreRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.GroupRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBookQueryRepository;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteCollectionBookReadAdapter;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookArtifactHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookQueryBuilder;
import com.myhomelibcorp.infrastructure.resource.BookResourceResolver;
import com.myhomelibcorp.ui.group.GroupMetabibExportService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.infrastructure.catalog.reader.MetabibCatalogReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Uses two real, independently migrated SQLite collection databases and the production
 * read-only adapter/resource resolver. This guards against tests passing only because
 * CollectionBookReadPort was mocked in the JSONL round-trip test.
 */
class CrossCollectionGroupExportSqliteIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void realCollectionDatabasesExportMatchingGroupsAndRelocateWithoutLosingSourceData() throws Exception {
        Path rootA = Files.createDirectories(temp.resolve("Бібліотека-А"));
        Path rootB = Files.createDirectories(temp.resolve("Бібліотека-Б"));
        Path databaseA = temp.resolve("collection-a.sqlite");
        Path databaseB = temp.resolve("collection-b.sqlite");

        String bytesA = "<FictionBook><description><title-info><book-title>Книга A</book-title></title-info></description></FictionBook>";
        String bytesB = "<FictionBook><description><title-info><book-title>Книга B</book-title></title-info></description></FictionBook>";
        Files.writeString(rootA.resolve("a.fb2"), bytesA, StandardCharsets.UTF_8);
        Files.writeString(rootB.resolve("b.fb2"), bytesB, StandardCharsets.UTF_8);

        String bookIdA = UUID.randomUUID().toString();
        String bookIdB = UUID.randomUUID().toString();
        long activeGroupId = createCollectionDatabase(databaseA, rootA, 3L, "Спільна група", bookIdA, "a.fb2",
                "Книга з колекції А", "893718", "genre-a", "Фантастика — А", bytesA.length());
        createCollectionDatabase(databaseB, rootB, 9L, "спільна ГРУПА", bookIdB, "b.fb2",
                "Книга з колекції Б", "999001", "genre-b", "Наукова фантастика — Б", bytesB.length());

        Collection primary = new Collection("collection-a", "Спільна назва бібліотеки", rootA,
                databaseA.toString(), 0, null, null, null, null, null);
        Collection secondary = new Collection("collection-b", "Спільна назва бібліотеки", rootB,
                databaseB.toString(), 0, null, null, null, null, null);

        CollectionManager manager = mock(CollectionManager.class);
        BookAuthorHelper authors = new BookAuthorHelper(manager, mock(AuthorRepository.class), new AuthorRowMapper());
        BookGenreHelper genres = new BookGenreHelper(manager, mock(GenreRepository.class), new GenreRowMapper());
        BookArtifactHelper artifacts = new BookArtifactHelper(manager);
        SqliteBookQueryRepository queries = new SqliteBookQueryRepository(manager, new BookRowMapper(),
                new BookListRowMapper(), authors, genres, artifacts, new BookQueryBuilder());
        CollectionBookReadPort readPort = new SqliteCollectionBookReadAdapter(queries, new GroupRowMapper());
        BookResourcePort resources = new BookResourceResolver(new ZipArchiveReader());

        CollectionRepository collectionRepository = mock(CollectionRepository.class);
        when(collectionRepository.findAll()).thenReturn(List.of(primary, secondary));
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(primary);

        var result = new GroupMetabibExportService(readPort, collectionRepository, resources, lifecycle)
                .export(activeGroupId, "collection-a", temp);

        assertThat(result.exported()).isEqualTo(2);
        assertThat(result.skipped()).isZero();

        Path relocated = temp.resolve("переміщений-пакет");
        Files.move(result.folder(), relocated);
        try (var imported = new MetabibCatalogReader(new ObjectMapper()).open(relocated.resolve("catalog.jsonl"))) {
            assertThat(imported.dataset().declaredRecords()).isEqualTo(2L);

            var first = imported.next();
            assertThat(first.title()).isEqualTo("Книга з колекції А");
            assertThat(first.sourceBookId()).contains("Спільна назва бібліотеки", "zip-0001");
            assertThat(first.externalIdentities()).anySatisfy(identity -> {
                assertThat(identity.scheme()).isEqualTo("catalog:mhl-source-book");
                assertThat(identity.value()).startsWith("12:collection-a:");
            });
            assertThat(first.archive()).isEqualTo("books-0001.zip");
            assertThat(first.archiveEntry()).isEqualTo("book-000001.fb2");
            assertThat(first.genres()).containsExactly("sf");
            assertThat(first.sourceMetadata().get("metabib.claims.json"))
                    .contains("\"name\":\"Фантастика — А\"")
                    .contains("\"source_code\":\"genre-a\"");

            var second = imported.next();
            assertThat(second.title()).isEqualTo("Книга з колекції Б");
            assertThat(second.sourceBookId()).contains("Спільна назва бібліотеки", "zip-0002");
            assertThat(second.externalIdentities()).anySatisfy(identity -> {
                assertThat(identity.scheme()).isEqualTo("catalog:mhl-source-book");
                assertThat(identity.value()).startsWith("12:collection-b:");
            });
            assertThat(second.sourceBookId()).isNotEqualTo(first.sourceBookId());
            assertThat(second.archive()).isEqualTo("books-0002.zip");
            assertThat(second.archiveEntry()).isEqualTo("book-000002.fb2");
            assertThat(second.genres()).containsExactly("sf");
            assertThat(second.sourceMetadata().get("metabib.claims.json"))
                    .contains("\"name\":\"Наукова фантастика — Б\"")
                    .contains("\"source_code\":\"genre-b\"");

            assertThat(imported.hasNext()).isFalse();
        }
        try (ZipFile firstZip = new ZipFile(relocated.resolve("books-0001.zip").toFile());
             ZipFile secondZip = new ZipFile(relocated.resolve("books-0002.zip").toFile())) {
            assertThat(firstZip.getEntry("book-000001.fb2")).isNotNull();
            assertThat(secondZip.getEntry("book-000002.fb2")).isNotNull();
            assertThat(new String(firstZip.getInputStream(firstZip.getEntry("book-000001.fb2")).readAllBytes(),
                    StandardCharsets.UTF_8)).isEqualTo(bytesA);
            assertThat(new String(secondZip.getInputStream(secondZip.getEntry("book-000002.fb2")).readAllBytes(),
                    StandardCharsets.UTF_8)).isEqualTo(bytesB);
        }
    }

    private static long createCollectionDatabase(Path database, Path root, long requestedGroupId, String groupName,
                                                  String bookId, String fileName, String title,
                                                  String libId, String genreCode, String genreName,
                                                  long fileSize) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Long maximumGroupId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM groups", Long.class);
        long groupId = Math.max(requestedGroupId, (maximumGroupId == null ? 0L : maximumGroupId) + 1L);
        jdbc.update("INSERT INTO groups(id, name, allow_delete) VALUES (?, ?, 1)", groupId, groupName);
        jdbc.update("""
                INSERT INTO books(
                    id, title, file_name, folder, archive_entry, file_size, collection_root,
                    language, year, rate, progress, annotation, keywords, isbn, publisher,
                    lib_id, library_rate, translators, city, source_url, deleted, local
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)
                """, bookId, title, fileName, "", "", fileSize, root.toString(),
                "uk", 2024, 4, 0, "Збережена анотація", "наука, фантастика",
                "9780306406157", "Видавництво", libId, 5, "Перекладач", "Київ", "", 0);
        jdbc.update("INSERT INTO book_groups(book_id, group_id) VALUES (?, ?)", bookId, groupId);

        String authorId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO authors(id, first_name, middle_name, last_name) VALUES (?,?,?,?)",
                authorId, "Олександр", "", "Король");
        jdbc.update("INSERT INTO book_authors(book_id, author_id) VALUES (?,?)", bookId, authorId);

        jdbc.update("INSERT INTO genres(code, name, parent_code, fb2_code) VALUES (?,?,NULL,?)",
                genreCode, genreName, "sf");
        jdbc.update("INSERT INTO book_genres(book_id, genre_code) VALUES (?,?)", bookId, genreCode);
        return groupId;
    }
}
