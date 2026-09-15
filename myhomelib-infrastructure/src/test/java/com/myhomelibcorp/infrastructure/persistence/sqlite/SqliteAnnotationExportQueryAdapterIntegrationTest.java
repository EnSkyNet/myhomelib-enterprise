package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.annotation.export.AnnotationExportSelection;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteAnnotationExportQueryAdapterIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void exportsOneSelectedMultipleSelectedAndAllBooksInDeterministicOrder() {
        Fixture f = fixture();
        insertBook(f.jdbc(), "b2", "Beta Book", "beta.fb2", "en", "ISBN-B");
        insertBook(f.jdbc(), "b1", "Alpha Book", "alpha.fb2", "uk", "ISBN-A");
        insertAuthor(f.jdbc(), "u2", "Grace", null, "Hopper");
        insertAuthor(f.jdbc(), "u1", "Ada", null, "Lovelace");
        f.jdbc().update("INSERT INTO book_authors(book_id,author_id) VALUES('b1','u1'),('b1','u2')");

        insertAnnotation(f.jdbc(), "a3", "b2", "NOTE", "#333333", "third note", "c3", "End", "third quote",
                0.9, "2026-09-10T12:00:00Z");
        insertAnnotation(f.jdbc(), "a2", "b1", "NOTE", "#222222", "second note", "c2", "Chapter 2", "second quote",
                0.6, "2026-09-10T09:00:00Z");
        insertAnnotation(f.jdbc(), "a1", "b1", "HIGHLIGHT", "#111111", "first note", "c1", "Intro", "first quote",
                0.2, "2026-09-10T10:00:00Z");
        f.jdbc().update("INSERT INTO annotation_tags(annotation_id,tag) VALUES('a1','zeta'),('a1','Alpha'),('a2','todo')");

        var one = f.adapter().query(AnnotationExportSelection.books(Set.of("b2")), 0, 10);
        assertThat(one.items()).extracting(item -> item.id()).containsExactly("a3");

        var selected = f.adapter().query(AnnotationExportSelection.books(Set.of("b2", "b1")), 0, 2);
        // Reading position, not annotation creation time, defines deterministic digest/export order.
        assertThat(selected.items()).extracting(item -> item.id()).containsExactly("a1", "a2");
        assertThat(selected.hasNext()).isTrue();
        assertThat(f.adapter().query(AnnotationExportSelection.books(Set.of("b2", "b1")), 2, 2).items())
                .extracting(item -> item.id()).containsExactly("a3");

        var all = f.adapter().query(AnnotationExportSelection.all(), 0, 10);
        assertThat(all.items()).extracting(item -> item.id()).containsExactly("a1", "a2", "a3");
        assertThat(all.items().getFirst().bookTitle()).isEqualTo("Alpha Book");
        assertThat(all.items().getFirst().authors()).isEqualTo("Grace Hopper, Ada Lovelace");
        assertThat(all.items().getFirst().language()).isEqualTo("uk");
        assertThat(all.items().getFirst().isbn()).isEqualTo("ISBN-A");
        assertThat(all.items().getFirst().chapterId()).isEqualTo("c1");
        assertThat(all.items().getFirst().tags()).containsExactly("Alpha", "zeta");
    }

    private static void insertBook(JdbcTemplate jdbc, String id, String title, String fileName, String language, String isbn) {
        jdbc.update("INSERT INTO books(id,title,series,file_name,language,isbn,deleted,local) VALUES(?,?,?,?,?,?,0,1)",
                id, title, "Series " + id, fileName, language, isbn);
    }

    private static void insertAuthor(JdbcTemplate jdbc, String id, String first, String middle, String last) {
        jdbc.update("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES(?,?,?,?)", id, first, middle, last);
    }

    private static void insertAnnotation(JdbcTemplate jdbc, String id, String bookId, String type, String color,
                                         String note, String chapterId, String chapterTitle, String quote,
                                         double position, String timestamp) {
        jdbc.update("""
                INSERT INTO annotations(id,book_id,annotation_type,color,note,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?)
                """, id, bookId, type, color, note, timestamp, timestamp);
        jdbc.update("""
                INSERT INTO annotation_anchors(annotation_id,chapter_id,chapter_title,start_offset,end_offset,position,
                                               quote_text,prefix_text,suffix_text)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, id, chapterId, chapterTitle, 0, Math.max(1, quote.length()), position, quote, "", "");
    }

    private Fixture fixture() {
        Path file = tempDir.resolve("annotation-export.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentJdbcTemplate(jdbc);
        manager.setCurrentDataSource(ds);
        return new Fixture(jdbc, new SqliteAnnotationExportQueryAdapter(new QueryExecutor(manager), manager));
    }

    private record Fixture(JdbcTemplate jdbc, SqliteAnnotationExportQueryAdapter adapter) { }
}
