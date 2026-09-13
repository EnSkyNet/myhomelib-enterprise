package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteFuzzyDuplicateCandidateLookupTest {
    @TempDir Path tempDir;

    @Test
    void retrievesOnlyBoundedTitleAuthorOrIsbnCandidates() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("fuzzy-candidates.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);

        String sourceId = "10000000-0000-0000-0000-000000000001";
        seed(jdbc, sourceId, "Clean Code", "9780306406157", "Robert", "Martin");
        seed(jdbc, "10000000-0000-0000-0000-000000000002", "Clean Code", "", "Robert", "Martin");
        seed(jdbc, "10000000-0000-0000-0000-000000000003", "Clean Coding Handbook", "", "Robert", "Martin");
        seed(jdbc, "10000000-0000-0000-0000-000000000004", "Totally Different", "9780306406157", "Someone", "Else");
        seed(jdbc, "10000000-0000-0000-0000-000000000005", "Clean Code", "", "Bob", "Writer");

        Book source = Book.builder()
                .id(BookId.fromString(sourceId))
                .title("Clean Code")
                .authors(List.of(new Author("Robert", "", "Martin")))
                .metadata(BookMetadata.builder().isbn(com.myhomelibcorp.domain.model.valueobject.Isbn.of("9780306406157")).build())
                .file(BookFile.empty())
                .build();

        SqliteFuzzyDuplicateCandidateLookup lookup = new SqliteFuzzyDuplicateCandidateLookup(manager);
        var ids = lookup.findCandidateIds(source, 3).stream().map(BookId::asString).toList();

        assertThat(ids).hasSize(3);
        assertThat(ids).contains("10000000-0000-0000-0000-000000000004");
        assertThat(ids).contains("10000000-0000-0000-0000-000000000002");
        assertThat(ids).doesNotContain(sourceId, "10000000-0000-0000-0000-000000000005");
    }

    private static void seed(JdbcTemplate jdbc, String bookId, String title, String isbn, String first, String last) {
        String authorId = "a-" + bookId;
        jdbc.update("INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,isbn,deleted,local) VALUES (?,?,?,?,?,?,?,?,?)",
                bookId, title, bookId + ".fb2", "", "", 1, isbn, 0, 1);
        jdbc.update("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES (?,?,?,?)", authorId, first, "", last);
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES (?,?)", bookId, authorId);
    }
}
