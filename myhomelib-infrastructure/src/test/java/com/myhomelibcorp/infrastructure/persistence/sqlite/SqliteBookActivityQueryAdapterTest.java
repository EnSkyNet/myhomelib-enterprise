package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteBookActivityQueryAdapterTest {
    @TempDir Path tempDir;

    @Test
    void summarizesNotesHighlightsAndBookmarksInBulk() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("activity.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE annotations(id TEXT PRIMARY KEY, book_id TEXT NOT NULL, annotation_type TEXT NOT NULL)");
        jdbc.execute("CREATE INDEX idx_annotations_book_type ON annotations(book_id, annotation_type)");
        jdbc.execute("CREATE TABLE bookmarks(id TEXT PRIMARY KEY, book_id TEXT NOT NULL)");
        jdbc.execute("CREATE INDEX idx_bookmarks_book_id ON bookmarks(book_id)");

        jdbc.update("INSERT INTO annotations(id,book_id,annotation_type) VALUES('a1','book-a','NOTE')");
        jdbc.update("INSERT INTO annotations(id,book_id,annotation_type) VALUES('a2','book-a','NOTE')");
        jdbc.update("INSERT INTO annotations(id,book_id,annotation_type) VALUES('a3','book-a','HIGHLIGHT')");
        jdbc.update("INSERT INTO annotations(id,book_id,annotation_type) VALUES('b1','book-b','HIGHLIGHT')");
        jdbc.update("INSERT INTO bookmarks(id,book_id) VALUES('m1','book-a')");
        jdbc.update("INSERT INTO bookmarks(id,book_id) VALUES('m2','book-a')");
        jdbc.update("INSERT INTO bookmarks(id,book_id) VALUES('m3','book-c')");

        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        SqliteBookActivityQueryAdapter adapter = new SqliteBookActivityQueryAdapter(manager);

        var result = adapter.summarize(List.of("book-a", "book-b", "book-c", "book-empty", "book-a"));

        assertThat(result).hasSize(4);
        assertThat(result.get("book-a").noteCount()).isEqualTo(2);
        assertThat(result.get("book-a").highlightCount()).isEqualTo(1);
        assertThat(result.get("book-a").bookmarkCount()).isEqualTo(2);
        assertThat(result.get("book-b").noteCount()).isZero();
        assertThat(result.get("book-b").highlightCount()).isEqualTo(1);
        assertThat(result.get("book-c").bookmarkCount()).isEqualTo(1);
        assertThat(result.get("book-empty").annotationCount()).isZero();
        assertThat(result.get("book-empty").bookmarkCount()).isZero();
    }
}
