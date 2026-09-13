package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.annotation.Annotation;
import com.myhomelibcorp.domain.model.annotation.AnnotationAnchor;
import com.myhomelibcorp.domain.model.annotation.AnnotationType;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteAnnotationRepositoryIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void annotationsRoundTripAcrossRepositoryRestartWithAnchorAndTags() {
        var fixture = fixture("annotations.db");
        fixture.jdbc().update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('book-1','Book','book.fb2',0,1)");
        fixture.jdbc().update("""
                INSERT INTO book_artifacts(artifact_id,book_id,artifact_name,file_name,local,remote,state)
                VALUES('artifact-1','book-1','book.fb2','book.fb2',1,0,'AVAILABLE')
                """);
        Instant created = Instant.parse("2026-09-09T10:00:00Z");
        Annotation annotation = new Annotation("ann-1", AnnotationType.HIGHLIGHT,
                new AnnotationAnchor("book-1", "artifact-1", "ch-1", "Chapter", "p-3",
                        30, 41, 0.3, "hello world", "before ", " after"),
                "#FFF59D", "memo", Set.of("one", "two"), created, created.plusSeconds(2));

        fixture.repository().save(annotation);
        SqliteAnnotationRepository reopened = new SqliteAnnotationRepository(
                new QueryExecutor(fixture.manager()), fixture.manager());

        assertThat(reopened.findById("ann-1")).contains(annotation);
        assertThat(reopened.findByBookId("book-1")).containsExactly(annotation);
        assertThat(reopened.countByBookId("book-1")).isEqualTo(1);

        reopened.deleteById("ann-1");
        assertThat(reopened.findById("ann-1")).isEmpty();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM annotation_anchors", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM annotation_tags", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM annotation_search_ids", Integer.class)).isZero();
        assertThat(fixture.jdbc().queryForObject("SELECT COUNT(*) FROM annotation_search_fts", Integer.class)).isZero();
    }

    @Test
    void listingLargeAnnotationSetUsesStableBookScopedOrder() {
        var fixture = fixture("annotations-large.db");
        fixture.jdbc().update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('book-1','Book','book.fb2',0,1)");
        int count = 5_000;
        fixture.jdbc().execute("""
                WITH RECURSIVE seq(i) AS (
                    VALUES(0) UNION ALL SELECT i+1 FROM seq WHERE i<4999
                )
                INSERT INTO annotations(id,book_id,annotation_type,color,note,created_at,updated_at)
                SELECT 'ann-' || i,'book-1','HIGHLIGHT','#FFF59D','',
                       strftime('%Y-%m-%dT%H:%M:%fZ',1700000000+i,'unixepoch'),
                       strftime('%Y-%m-%dT%H:%M:%fZ',1700000000+i,'unixepoch')
                  FROM seq
                """);
        fixture.jdbc().execute("""
                WITH RECURSIVE seq(i) AS (
                    VALUES(0) UNION ALL SELECT i+1 FROM seq WHERE i<4999
                )
                INSERT INTO annotation_anchors(annotation_id,start_offset,end_offset,position,quote_text,prefix_text,suffix_text)
                SELECT 'ann-' || i,i,i+1,MIN(1.0,i/5000.0),'x','','' FROM seq
                """);

        var all = fixture.repository().findByBookId("book-1");
        assertThat(all).hasSize(count);
        assertThat(all.getFirst().id()).isEqualTo("ann-4999");
        assertThat(all.getLast().id()).isEqualTo("ann-0");
    }

    private Fixture fixture(String name) {
        Path file = tempDir.resolve(name);
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + file.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentJdbcTemplate(jdbc);
        manager.setCurrentDataSource(ds);
        return new Fixture(jdbc, manager, new SqliteAnnotationRepository(new QueryExecutor(manager), manager));
    }

    private record Fixture(JdbcTemplate jdbc, TestCollectionManager manager, SqliteAnnotationRepository repository) { }
}
