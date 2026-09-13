package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.annotation.AnnotationManagerFilter;
import com.myhomelibcorp.application.annotation.AnnotationManagerType;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteAnnotationManagerQueryAdapterIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void filtersSearchFacetsAndPaginationAreExecutedBySqlite() {
        Fixture f = fixture();
        f.jdbc().update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('b1','Alpha Book','a.fb2',0,1)");
        f.jdbc().update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('b2','Beta Book','b.fb2',0,1)");
        insert(f.jdbc(), "a1", "b1", "HIGHLIGHT", "#FFF59D", "", "Intro", "needle quote", "2026-09-07T10:00:00Z");
        insert(f.jdbc(), "a2", "b1", "NOTE", "#AABBCC", "private memo", "Chapter 2", "other quote", "2026-09-08T10:00:00Z");
        insert(f.jdbc(), "a3", "b2", "HIGHLIGHT", "#FFF59D", "review text", "End", "third quote", "2026-09-09T10:00:00Z");
        f.jdbc().update("INSERT INTO annotation_tags(annotation_id,tag) VALUES('a1','research'),('a2','todo'),('a3','research')");

        var firstPage = f.adapter().query(AnnotationManagerFilter.empty(), 0, 2);
        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(item -> item.id()).containsExactly("a3", "a2");
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(f.adapter().query(AnnotationManagerFilter.empty(), 2, 2).items())
                .extracting(item -> item.id()).containsExactly("a1");

        var search = f.adapter().query(new AnnotationManagerFilter("needle", null, null, null, null, null, null), 0, 20);
        assertThat(search.items()).extracting(item -> item.id()).containsExactly("a1");

        var tagAndType = f.adapter().query(new AnnotationManagerFilter(null, null, AnnotationManagerType.HIGHLIGHT,
                "#fff59d", "research", LocalDate.parse("2026-09-08"), LocalDate.parse("2026-09-09")), 0, 20);
        assertThat(tagAndType.items()).extracting(item -> item.id()).containsExactly("a3");

        var book = f.adapter().query(new AnnotationManagerFilter(null, "b1", null, null, null, null, null), 0, 20);
        assertThat(book.total()).isEqualTo(2);
        assertThat(book.items().getFirst().bookTitle()).isEqualTo("Alpha Book");
        assertThat(book.items().getLast().tags()).containsExactly("research");

        var facets = f.adapter().facets();
        assertThat(facets.books()).extracting(item -> item.title()).containsExactly("Alpha Book", "Beta Book");
        assertThat(facets.colors()).containsExactly("#AABBCC", "#FFF59D");
        assertThat(facets.tags()).containsExactly("research", "todo");
    }

    @Test
    void wildcardCharactersInSearchAreLiteralNotPatternExpansion() {
        Fixture f = fixture();
        f.jdbc().update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('b1','100% Book','a.fb2',0,1)");
        insert(f.jdbc(), "a1", "b1", "HIGHLIGHT", "#FFF59D", "", "Intro", "value_1", "2026-09-09T10:00:00Z");
        insert(f.jdbc(), "a2", "b1", "HIGHLIGHT", "#FFF59D", "", "Intro", "valueX1", "2026-09-09T11:00:00Z");

        assertThat(f.adapter().query(new AnnotationManagerFilter("value_1", null, null, null, null, null, null), 0, 20).items())
                .extracting(item -> item.id()).containsExactly("a1");
        assertThat(f.adapter().query(new AnnotationManagerFilter("100%", null, null, null, null, null, null), 0, 20).total())
                .isEqualTo(2);
    }


    @Test
    void fullTextIndexTracksNoteAnchorTagAndBookTitleUpdates() {
        Fixture f = fixture();
        f.jdbc().update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('b1','Original Book','a.fb2',0,1)");
        insert(f.jdbc(), "a1", "b1", "NOTE", "#AABBCC", "initial memo", "Intro", "initial quote", "2026-09-09T10:00:00Z");
        f.jdbc().update("INSERT INTO annotation_tags(annotation_id,tag) VALUES('a1','firsttag')");

        assertThat(f.adapter().query(new AnnotationManagerFilter("initial memo", null, null, null, null, null, null), 0, 20).total()).isEqualTo(1);
        assertThat(f.adapter().query(new AnnotationManagerFilter("firsttag", null, null, null, null, null, null), 0, 20).total()).isEqualTo(1);

        f.jdbc().update("UPDATE annotations SET note='updated memo' WHERE id='a1'");
        f.jdbc().update("UPDATE annotation_anchors SET quote_text='updated quote',chapter_title='Updated chapter' WHERE annotation_id='a1'");
        f.jdbc().update("UPDATE annotation_tags SET tag='updatedtag' WHERE annotation_id='a1'");
        f.jdbc().update("UPDATE books SET title='Renamed Book' WHERE id='b1'");

        assertThat(f.adapter().query(new AnnotationManagerFilter("updated memo", null, null, null, null, null, null), 0, 20).total()).isEqualTo(1);
        assertThat(f.adapter().query(new AnnotationManagerFilter("updated quote", null, null, null, null, null, null), 0, 20).total()).isEqualTo(1);
        assertThat(f.adapter().query(new AnnotationManagerFilter("Updated chapter", null, null, null, null, null, null), 0, 20).total()).isEqualTo(1);
        assertThat(f.adapter().query(new AnnotationManagerFilter("updatedtag", null, null, null, null, null, null), 0, 20).total()).isEqualTo(1);
        assertThat(f.adapter().query(new AnnotationManagerFilter("Renamed Book", null, null, null, null, null, null), 0, 20).total()).isEqualTo(1);
        assertThat(f.adapter().query(new AnnotationManagerFilter("initial memo", null, null, null, null, null, null), 0, 20).total()).isZero();
    }

    @Test
    void stableFtsRowIdSurvivesVacuumAndSubsequentRefresh() {
        Fixture f = fixture();
        f.jdbc().update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('b1','Vacuum Book','a.fb2',0,1)");
        insert(f.jdbc(), "a0", "b1", "NOTE", "#AABBCC", "temporary", "Intro", "temporary quote", "2026-09-09T09:00:00Z");
        insert(f.jdbc(), "a1", "b1", "NOTE", "#AABBCC", "before vacuum", "Chapter", "durable quote", "2026-09-09T10:00:00Z");

        Long stableRowId = f.jdbc().queryForObject(
                "SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id='a1'", Long.class);
        f.jdbc().update("DELETE FROM annotations WHERE id='a0'");
        f.jdbc().execute("VACUUM");

        assertThat(f.jdbc().queryForObject(
                "SELECT fts_rowid FROM annotation_search_ids WHERE annotation_id='a1'", Long.class))
                .isEqualTo(stableRowId);

        f.jdbc().update("UPDATE annotations SET note='after vacuum' WHERE id='a1'");
        assertThat(f.jdbc().queryForObject(
                "SELECT COUNT(*) FROM annotation_search_fts WHERE rowid=? AND annotation_id='a1'", Integer.class, stableRowId))
                .isEqualTo(1);
        assertThat(f.jdbc().queryForObject(
                "SELECT COUNT(*) FROM annotation_search_fts WHERE annotation_id='a1'", Integer.class))
                .isEqualTo(1);
        assertThat(f.adapter().query(new AnnotationManagerFilter("after vacuum", null, null, null, null, null, null), 0, 20).total())
                .isEqualTo(1);
        assertThat(f.adapter().query(new AnnotationManagerFilter("before vacuum", null, null, null, null, null, null), 0, 20).total())
                .isZero();
    }

    private static void insert(JdbcTemplate jdbc, String id, String bookId, String type, String color,
                               String note, String chapter, String quote, String updated) {
        jdbc.update("""
                INSERT INTO annotations(id,book_id,annotation_type,color,note,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?)
                """, id, bookId, type, color, note, updated, updated);
        jdbc.update("""
                INSERT INTO annotation_anchors(annotation_id,chapter_title,start_offset,end_offset,position,quote_text,prefix_text,suffix_text)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, chapter, 0, Math.max(1, quote.length()), 0.2, quote, "", "");
    }

    private Fixture fixture() {
        Path file = tempDir.resolve("annotation-manager.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentJdbcTemplate(jdbc);
        manager.setCurrentDataSource(ds);
        return new Fixture(jdbc, new SqliteAnnotationManagerQueryAdapter(new QueryExecutor(manager), manager));
    }

    private record Fixture(JdbcTemplate jdbc, SqliteAnnotationManagerQueryAdapter adapter) { }
}
