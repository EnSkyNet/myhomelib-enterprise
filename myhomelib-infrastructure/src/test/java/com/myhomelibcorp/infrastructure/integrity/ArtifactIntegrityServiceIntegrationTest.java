package com.myhomelibcorp.infrastructure.integrity;

import com.myhomelibcorp.application.integrity.ArtifactIntegrityStatus;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import com.myhomelibcorp.infrastructure.persistence.sqlite.TestCollectionManager;
import com.myhomelibcorp.infrastructure.resource.BookResourceResolver;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactIntegrityServiceIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void unchangedArtifactReusesHashAndLaterContentChangeIsReportedWithoutRewritingCatalogBaseline() throws Exception {
        Path db = tempDir.resolve("audit.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        Path book = tempDir.resolve("book.fb2");
        byte[] original = "original-content".getBytes(StandardCharsets.UTF_8);
        Files.write(book, original);
        String baselineHash = sha256(original);
        insertBookAndArtifact(jdbc, "11111111-1111-1111-1111-111111111111", "a1", "book.fb2",
                original.length, baselineHash);

        ArtifactIntegrityService service = service(jdbc, ds);
        var first = service.auditIncremental(100);
        assertThat(first.healthyArtifacts()).isEqualTo(1);
        assertThat(first.reusedArtifacts()).isZero();
        assertThat(first.bytesRead()).isEqualTo(original.length);

        var second = service.auditIncremental(100);
        assertThat(second.healthyArtifacts()).isEqualTo(1);
        assertThat(second.reusedArtifacts()).isEqualTo(1);
        assertThat(second.bytesRead()).isZero();

        byte[] changed = "changed-content-longer".getBytes(StandardCharsets.UTF_8);
        Files.write(book, changed);
        Files.setLastModifiedTime(book, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000));
        var third = service.auditIncremental(100);
        assertThat(third.changedArtifacts()).isEqualTo(1);
        assertThat(third.findings()).singleElement().extracting(f -> f.status()).isEqualTo(ArtifactIntegrityStatus.HASH_CHANGED);
        assertThat(jdbc.queryForObject("SELECT sha256 FROM book_artifacts WHERE artifact_id='a1'", String.class))
                .isEqualTo(baselineHash);
    }

    @Test
    void reportsMissingAndCorruptArchiveAsSeparateNonDestructiveFindings() throws Exception {
        Path db = tempDir.resolve("missing-corrupt.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        insertBookAndArtifact(jdbc, "11111111-1111-1111-1111-111111111111", "missing", "missing.fb2", -1, "");
        Path corrupt = tempDir.resolve("broken.zip");
        Files.writeString(corrupt, "not-a-zip", StandardCharsets.UTF_8);
        insertBookAndArtifact(jdbc, "22222222-2222-2222-2222-222222222222", "corrupt", "broken.zip", -1, "");

        var report = service(jdbc, ds).auditIncremental(100);
        assertThat(report.missingArtifacts()).isEqualTo(1);
        assertThat(report.corruptArtifacts()).isEqualTo(1);
        assertThat(report.findings()).extracting(f -> f.status())
                .containsExactlyInAnyOrder(ArtifactIntegrityStatus.MISSING, ArtifactIntegrityStatus.CORRUPT_ARCHIVE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM books", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_artifacts", Integer.class)).isEqualTo(2);
    }

    private ArtifactIntegrityService service(JdbcTemplate jdbc, javax.sql.DataSource ds) {
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentJdbcTemplate(jdbc);
        manager.setCurrentDataSource(ds);
        return new ArtifactIntegrityService(manager, new BookResourceResolver(new ZipArchiveReader()));
    }

    private void insertBookAndArtifact(JdbcTemplate jdbc, String bookId, String artifactId,
                                       String fileName, long size, String hash) {
        jdbc.update("INSERT INTO books(id,title,file_name,folder,collection_root,deleted,local) VALUES(?,?,?,?,?,0,1)",
                bookId, "Book " + artifactId, fileName, "", tempDir.toString());
        jdbc.update("""
                INSERT INTO book_artifacts(
                    artifact_id,book_id,artifact_name,file_name,size_bytes,sha256,local,remote,state,collection_root,folder
                ) VALUES(?,?,?,?,?,?,1,0,'AVAILABLE',?,?)
                """, artifactId, bookId, fileName, fileName, size < 0 ? null : size,
                hash == null || hash.isBlank() ? null : hash, tempDir.toString(), "");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
