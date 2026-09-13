package com.myhomelibcorp.infrastructure.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnnotationBackupRoundTripIntegrationTest {
    @TempDir Path tempDir;

    @AfterEach
    void cleanup() {
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void schemaV4RoundTripKeepsAnnotationAnchorAndTagsAndSafelyDropsForeignArtifactBinding() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.resolve("appdata").toString());
        Files.createDirectories(tempDir.resolve("appdata/config"));
        Db source = db("source.db");
        source.jdbc().update("INSERT INTO books(id,title,file_name,lib_id,deleted,local) VALUES('old-book','Book','book.fb2','LIB-ANN',0,1)");
        source.jdbc().update("INSERT INTO book_artifacts(artifact_id,book_id,artifact_name,file_name,local,remote,state) VALUES('source-artifact','old-book','book.fb2','book.fb2',1,0,'AVAILABLE')");
        source.jdbc().update("INSERT INTO annotations(id,book_id,artifact_id,annotation_type,color,note,created_at,updated_at) VALUES('ann-1','old-book','source-artifact','NOTE','#AABBCC','remember','2026-09-09T10:00:00Z','2026-09-09T10:01:00Z')");
        source.jdbc().update("INSERT INTO annotation_anchors(annotation_id,chapter_id,chapter_title,paragraph_id,start_offset,end_offset,position,quote_text,prefix_text,suffix_text) VALUES('ann-1','ch','Chapter','p',12,18,0.25,'quoted','before','after')");
        source.jdbc().update("INSERT INTO annotation_tags(annotation_id,tag) VALUES('ann-1','tag-a'),('ann-1','tag-b')");

        Path manifest = tempDir.resolve("user-data.json");
        VersionedUserDataTransferAdapter sourceAdapter = new VersionedUserDataTransferAdapter(manager(source), new MapSettings(), new ObjectMapper());
        var exported = sourceAdapter.exportTo(manifest);
        assertThat(exported.schemaVersion()).isEqualTo(4);
        assertThat(exported.annotations()).isEqualTo(1);
        var json = new ObjectMapper().readTree(manifest.toFile());
        assertThat(json.path("annotations").size()).isEqualTo(1);
        assertThat(json.path("annotationTags").size()).isEqualTo(2);

        Db target = db("target.db");
        target.jdbc().update("INSERT INTO books(id,title,file_name,lib_id,deleted,local) VALUES('new-book','Book','book.fb2','LIB-ANN',0,1)");
        target.jdbc().update("INSERT INTO book_artifacts(artifact_id,book_id,artifact_name,file_name,local,remote,state) VALUES('target-artifact','new-book','book.fb2','book.fb2',1,0,'AVAILABLE')");
        VersionedUserDataTransferAdapter targetAdapter = new VersionedUserDataTransferAdapter(manager(target), new MapSettings(), new ObjectMapper());
        var restored = targetAdapter.restoreFrom(manifest);
        assertThat(restored.effectiveSchemaVersion()).isEqualTo(4);
        assertThat(restored.annotations()).isEqualTo(1);

        assertThat(target.jdbc().queryForObject("SELECT book_id FROM annotations WHERE id='ann-1'", String.class)).isEqualTo("new-book");
        assertThat(target.jdbc().queryForObject("SELECT artifact_id FROM annotations WHERE id='ann-1'", String.class)).isNull();
        assertThat(target.jdbc().queryForObject("SELECT quote_text FROM annotation_anchors WHERE annotation_id='ann-1'", String.class)).isEqualTo("quoted");
        assertThat(target.jdbc().queryForObject("SELECT color FROM annotations WHERE id='ann-1'", String.class)).isEqualTo("#AABBCC");
        assertThat(target.jdbc().queryForList("SELECT tag FROM annotation_tags WHERE annotation_id='ann-1' ORDER BY tag", String.class))
                .containsExactlyInAnyOrder("tag-a", "tag-b");

        // A repeated restore must replace the annotation tag snapshot rather than append to it.
        target.jdbc().update("INSERT INTO annotation_tags(annotation_id,tag) VALUES('ann-1','stale-local-tag')");
        targetAdapter.restoreFrom(manifest);
        assertThat(target.jdbc().queryForObject("SELECT COUNT(*) FROM annotations WHERE id='ann-1'", Integer.class)).isEqualTo(1);
        assertThat(target.jdbc().queryForList("SELECT tag FROM annotation_tags WHERE annotation_id='ann-1' ORDER BY tag", String.class))
                .containsExactlyInAnyOrder("tag-a", "tag-b");
    }

    private Db db(String name) {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve(name).toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return new Db(ds, new JdbcTemplate(ds));
    }

    private static CollectionManager manager(Db db) {
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(db.jdbc());
        when(manager.getCurrentDataSource()).thenReturn(db.dataSource());
        return manager;
    }

    private record Db(javax.sql.DataSource dataSource, JdbcTemplate jdbc) { }

    private static class MapSettings implements ApplicationSettingsPort {
        private final Map<String,String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String,String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }
}
