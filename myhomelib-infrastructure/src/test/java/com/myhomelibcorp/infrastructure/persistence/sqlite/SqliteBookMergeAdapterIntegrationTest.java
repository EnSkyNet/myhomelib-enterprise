package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.duplicate.merge.BookMergePlan;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.cache.BookCache;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteBookMergeAdapterIntegrationTest {
    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final String B = "22222222-2222-2222-2222-222222222222";

    @TempDir Path tempDir;

    @Test
    void mergeThenUndoRestoresLogicalStateAndNeverDeletesPhysicalFiles() throws Exception {
        Path db = tempDir.resolve("merge.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentDataSource(ds);
        manager.setCurrentJdbcTemplate(jdbc);
        SqliteBookMergeAdapter adapter = new SqliteBookMergeAdapter(manager, new ObjectMapper(), new BookCache(manager));

        Path fileA = Files.writeString(tempDir.resolve("a.epub"), "a");
        Path fileB = Files.writeString(tempDir.resolve("b.pdf"), "b");
        seed(jdbc, fileA, fileB);

        var merged = adapter.merge(new BookMergePlan(
                BookId.fromString(A), BookId.fromString(B), BookId.fromString(B)));

        assertThat(jdbc.queryForObject("SELECT title FROM books WHERE id=?", String.class, A)).isEqualTo("Candidate metadata");
        assertThat(jdbc.queryForObject("SELECT rate FROM books WHERE id=?", Integer.class, A)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT progress FROM books WHERE id=?", Integer.class, A)).isEqualTo(70);
        assertThat(jdbc.queryForObject("SELECT deleted FROM books WHERE id=?", Integer.class, B)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_artifacts WHERE book_id=?", Integer.class, A)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_artifacts WHERE book_id=?", Integer.class, B)).isZero();
        assertThat(jdbc.queryForObject("SELECT book_id FROM bookmarks WHERE id='bookmark-b'", String.class)).isEqualTo(A);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_authors WHERE book_id=?", Integer.class, A)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT percent FROM reading_progress WHERE book_id=?", Integer.class, A)).isEqualTo(70);
        assertThat(jdbc.queryForObject("SELECT open_count FROM reading_history WHERE book_id=?", Integer.class, A)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT total_reading_seconds FROM reading_stats WHERE book_id=?", Integer.class, A)).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_identities WHERE book_id=?", Integer.class, A)).isEqualTo(1);
        assertThat(adapter.findLatestActiveMerge(BookId.fromString(A))).isPresent();
        assertThat(Files.exists(fileA)).isTrue();
        assertThat(Files.exists(fileB)).isTrue();

        var undone = adapter.undo(merged.mergeId());
        assertThat(undone.undone()).isTrue();
        assertThat(jdbc.queryForObject("SELECT title FROM books WHERE id=?", String.class, A)).isEqualTo("Survivor metadata");
        assertThat(jdbc.queryForObject("SELECT rate FROM books WHERE id=?", Integer.class, A)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT progress FROM books WHERE id=?", Integer.class, A)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT deleted FROM books WHERE id=?", Integer.class, B)).isZero();
        assertThat(jdbc.queryForObject("SELECT book_id FROM book_artifacts WHERE artifact_id='artifact-a'", String.class)).isEqualTo(A);
        assertThat(jdbc.queryForObject("SELECT book_id FROM book_artifacts WHERE artifact_id='artifact-b'", String.class)).isEqualTo(B);
        assertThat(jdbc.queryForObject("SELECT book_id FROM bookmarks WHERE id='bookmark-b'", String.class)).isEqualTo(B);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_authors WHERE book_id=?", Integer.class, A)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT percent FROM reading_progress WHERE book_id=?", Integer.class, A)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT open_count FROM reading_history WHERE book_id=?", Integer.class, A)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT total_reading_seconds FROM reading_stats WHERE book_id=?", Integer.class, A)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_identities WHERE book_id=?", Integer.class, B)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id=?", String.class, A)).isEqualTo("artifact-a");
        assertThat(jdbc.queryForObject("SELECT preferred_artifact_id FROM book_artifact_preferences WHERE book_id=?", String.class, B)).isEqualTo("artifact-b");
        assertThat(adapter.findLatestActiveMerge(BookId.fromString(A))).isEmpty();
        assertThat(Files.exists(fileA)).isTrue();
        assertThat(Files.exists(fileB)).isTrue();
    }

    private void seed(JdbcTemplate jdbc, Path fileA, Path fileB) {
        jdbc.update("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('author-a','Alice','','Author')");
        jdbc.update("INSERT INTO authors(id,first_name,middle_name,last_name) VALUES ('author-b','Bob','','Writer')");
        jdbc.update("INSERT INTO genres(code,name,parent_code,fb2_code) VALUES ('g1','Genre 1',NULL,'g1')");
        jdbc.update("INSERT INTO genres(code,name,parent_code,fb2_code) VALUES ('g2','Genre 2',NULL,'g2')");
        jdbc.update("INSERT INTO groups(name,allow_delete) VALUES ('Merge Test',1)");
        Integer groupId = jdbc.queryForObject("SELECT id FROM groups WHERE name='Merge Test'", Integer.class);

        jdbc.update("""
                INSERT INTO books(id,title,series,sequence_number,file_name,folder,archive_entry,language,file_size,
                    keywords,annotation,rate,progress,update_date,isbn,deleted,local,review,created_at,collection_root,
                    year,publisher,format,author_sort)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, A, "Survivor metadata", "S1", 1, fileA.getFileName().toString(), tempDir.toString(), "", "uk", 1,
                "alpha", "survivor annotation", 2, 20, "2026-09-01 10:00:00", "", 0, 1, "review A", "2026-01-01 00:00:00",
                tempDir.toString(), 2020, "Publisher A", "EPUB", "author");
        jdbc.update("""
                INSERT INTO books(id,title,series,sequence_number,file_name,folder,archive_entry,language,file_size,
                    keywords,annotation,rate,progress,update_date,isbn,deleted,local,review,created_at,collection_root,
                    year,publisher,format,author_sort)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, B, "Candidate metadata", "S2", 2, fileB.getFileName().toString(), tempDir.toString(), "", "uk", 1,
                "beta", "candidate annotation", 5, 70, "2026-09-02 10:00:00", "9780306406157", 0, 1, "review B", "2026-01-02 00:00:00",
                tempDir.toString(), 2024, "Publisher B", "PDF", "writer");

        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES (?, 'author-a')", A);
        jdbc.update("INSERT INTO book_authors(book_id,author_id) VALUES (?, 'author-b')", B);
        jdbc.update("INSERT INTO book_genres(book_id,genre_code) VALUES (?, 'g1')", A);
        jdbc.update("INSERT INTO book_genres(book_id,genre_code) VALUES (?, 'g2')", B);
        jdbc.update("INSERT INTO book_groups(book_id,group_id) VALUES (?, ?)", B, groupId);
        jdbc.update("INSERT OR IGNORE INTO keywords(normalized_name,display_name) VALUES ('alpha','alpha'),('beta','beta')");
        jdbc.update("INSERT INTO keyword_books(normalized_name,book_id) VALUES ('alpha',?),('beta',?)", A, B);

        jdbc.update("""
                INSERT INTO book_artifacts(artifact_id,book_id,artifact_name,file_format,file_name,size_bytes,remote,local,
                    collection_root,folder,state) VALUES ('artifact-a',?,'a.epub','epub',?,1,0,1,?,?,'AVAILABLE')
                """, A, fileA.getFileName().toString(), tempDir.toString(), tempDir.toString());
        jdbc.update("""
                INSERT INTO book_artifacts(artifact_id,book_id,artifact_name,file_format,file_name,size_bytes,remote,local,
                    collection_root,folder,state) VALUES ('artifact-b',?,'b.pdf','pdf',?,1,0,1,?,?,'AVAILABLE')
                """, B, fileB.getFileName().toString(), tempDir.toString(), tempDir.toString());
        jdbc.update("INSERT INTO book_artifact_preferences(book_id,preferred_artifact_id) VALUES (?, 'artifact-a'),(?, 'artifact-b')", A, B);
        jdbc.update("INSERT INTO bookmarks(id,book_id,paragraph_id,char_offset,position,created_at) VALUES ('bookmark-b',?,'p7',0,0.7,'2026-09-02')", B);
        jdbc.update("INSERT INTO book_identities(book_id,source_id,scheme,external_id) VALUES (?, 'src','isbn','identity-b')", B);

        jdbc.update("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,percent,updated_at,anchor_id,paragraph_index) VALUES (?, 'p2',0,20,'2026-09-01 10:00:00','p2',2)", A);
        jdbc.update("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,percent,updated_at,anchor_id,paragraph_index) VALUES (?, 'p7',0,70,'2026-09-02 10:00:00','p7',7)", B);
        jdbc.update("INSERT INTO reading_history(book_id,last_opened_at,open_count) VALUES (?, '2026-09-01 10:00:00',2),(?, '2026-09-02 10:00:00',3)", A, B);
        jdbc.update("""
                INSERT INTO reading_stats(book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,start_percent,end_percent,current_percent)
                VALUES (?, '2026-08-01','2026-09-01',100,1,0,20,20),(?, '2026-08-02','2026-09-02',200,2,0,70,70)
                """, A, B);
        jdbc.update("INSERT INTO reader_book_preferences(book_id,preferences_json) VALUES (?, '{\"font\":\"A\"}'),(?, '{\"font\":\"B\"}')", A, B);
    }
}
