package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcBatchWriterStage6Test {
    @Test
    void remoteUpsertPreservesLocalStorageAndUserDataIncludingBookmarks() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:sqlite:file:stage6-writer-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setMaximumPoolSize(2);
        try {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            JdbcBatchWriter writer = new JdbcBatchWriter(manager);

            jdbc.update("""
                    INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,rate,progress,review,
                                      local,deleted,collection_root,created_at)
                    VALUES('book','Old','local.fb2','downloads/local.zip','local.fb2',777,5,42,'my review',
                           1,0,'/downloads','2024-01-01 00:00:00.000')
                    """);
            jdbc.update("""
                    INSERT INTO bookmarks(id,book_id,paragraph_id,char_offset,position,chapter_title,context,created_at)
                    VALUES('bm','book','p1',0,1.0,'chapter','keep me','2024-01-01 00:00:00')
                    """);

            Object[] row = stage6Row();
            writer.batchInsertFull(List.<Object[]>of(row), new HashMap<>(), new HashMap<>());

            var stored = jdbc.queryForMap("""
                    SELECT title,file_name,folder,archive_entry,file_size,rate,progress,review,local,collection_root
                      FROM books WHERE id='book'
                    """);
            assertThat(stored.get("title")).isEqualTo("Updated catalog title");
            assertThat(stored.get("file_name")).isEqualTo("local.fb2");
            assertThat(stored.get("folder")).isEqualTo("downloads/local.zip");
            assertThat(stored.get("archive_entry")).isEqualTo("local.fb2");
            assertThat(((Number) stored.get("file_size")).longValue()).isEqualTo(777L);
            assertThat(((Number) stored.get("rate")).intValue()).isEqualTo(5);
            assertThat(((Number) stored.get("progress")).intValue()).isEqualTo(42);
            assertThat(stored.get("review")).isEqualTo("my review");
            assertThat(((Number) stored.get("local")).intValue()).isEqualTo(1);
            assertThat(stored.get("collection_root")).isEqualTo("/downloads");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookmarks WHERE book_id='book'", Integer.class))
                    .isEqualTo(1);
        } finally {
            ds.close();
        }
    }


    @Test
    void resolvesAuthorNamesContainingPipeWithoutLosingPersistentId() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:sqlite:file:stage6-author-pipe-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setMaximumPoolSize(2);
        try {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            JdbcBatchWriter writer = new JdbcBatchWriter(manager);

            Author author = new Author("Дамский клуб LADY | переводы", "", "Группа");
            var resolved = writer.batchInsertAuthorsAndResolveIds(List.of(author));

            String key = "Дамский клуб LADY | переводы||Группа";
            assertThat(resolved).containsKey(key);
            assertThat(resolved.get(key)).isEqualTo(jdbc.queryForObject(
                    "SELECT id FROM authors WHERE first_name=? AND last_name=?",
                    String.class,
                    "Дамский клуб LADY | переводы", "Группа"));
        } finally {
            ds.close();
        }
    }

    private static Object[] stage6Row() {
        Object[] row = new Object[29];
        row[0] = "book"; row[1] = "Updated catalog title"; row[2] = ""; row[3] = 0;
        row[4] = "remote-v2.fb2"; row[5] = "catalog-v2.zip"; row[6] = "remote-v2.fb2"; row[7] = "uk";
        row[8] = 200L; row[9] = "new-keyword"; row[10] = "new annotation"; row[11] = 0; row[12] = 0;
        row[13] = "2026-08-24 20:00:00.000"; row[14] = ""; row[15] = 0; row[16] = 0;
        row[17] = ""; row[18] = "2026-08-24 20:00:00.000"; row[19] = ""; row[20] = "";
        row[21] = "/remote"; row[22] = 2026; row[23] = "Publisher"; row[24] = "lib"; row[25] = 0;
        row[26] = ""; row[27] = ""; row[28] = "catalog:source";
        return row;
    }
}
