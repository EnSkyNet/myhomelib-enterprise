package com.myhomelibcorp.infrastructure.catalog.reader;

import tools.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdOutputStream;
import com.myhomelibcorp.application.catalog.importing.CatalogGenreDefinition;
import com.myhomelibcorp.application.catalog.importing.CatalogReadSession;
import com.myhomelibcorp.application.catalog.importing.CatalogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MetabibCatalogReaderTest {
    @TempDir Path temp;
    private final MetabibCatalogReader reader = new MetabibCatalogReader(new ObjectMapper());

    @Test
    void headerFailuresReleaseAllContainerHandles() throws Exception {
        for (String header : new String[]{"{invalid-json", "{\"schema\":\"wrong\"}", ""}) {
            for (String suffix : new String[]{".jsonl", ".jsonl.gz", ".jsonl.zst", ".zip"}) {
                Path source = temp.resolve("invalid" + suffix);
                byte[] bytes = header.getBytes(StandardCharsets.UTF_8);
                if (suffix.equals(".zip")) {
                    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(source))) {
                        out.putNextEntry(new ZipEntry("dataset.jsonl"));
                        out.write(bytes);
                        out.closeEntry();
                    }
                } else {
                    try (OutputStream raw = Files.newOutputStream(source);
                         OutputStream out = suffix.endsWith(".gz") ? new GZIPOutputStream(raw)
                                 : suffix.endsWith(".zst") ? new ZstdOutputStream(raw) : raw) {
                        out.write(bytes);
                    }
                }
                assertThatThrownBy(() -> reader.open(source)).isInstanceOf(RuntimeException.class);
                Files.delete(source);
                // On Windows an unclosed handle may leave the name delete-pending.
                Files.writeString(source, "released", java.nio.file.StandardOpenOption.CREATE_NEW);
                Files.delete(source);
            }
        }
    }

    @Test
    void readsJsonlGzipZstdAndZipStreamingContainers() throws Exception {
        String data = dataset();
        Path jsonl = temp.resolve("books.jsonl");
        Files.writeString(jsonl, data, StandardCharsets.UTF_8);

        Path gz = temp.resolve("books.jsonl.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
        }

        Path zst = temp.resolve("books.jsonl.zst");
        try (OutputStream out = new ZstdOutputStream(Files.newOutputStream(zst))) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
        }

        Path zip = temp.resolve("books.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("dataset/books.jsonl"));
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        for (Path source : new Path[]{jsonl, gz, zst, zip}) {
            assertThat(reader.supports(source)).isTrue();
            try (CatalogReadSession session = reader.open(source)) {
                assertThat(session.dataset().schema()).isEqualTo("metabib.dataset/1");
                assertThat(session.hasNext()).isTrue();
                CatalogRecord record = session.next();
                assertThat(record.title()).isEqualTo("Book title");
                assertThat(record.language()).isEqualTo("en");
                assertThat(record.authors()).hasSize(1);
                assertThat(record.authors().getFirst().firstName()).isEqualTo("Ann|Pipe");
                assertThat(record.authors().getFirst().identities()).hasSize(1);
                assertThat(session.hasNext()).isFalse();
            }
        }
    }

    @Test
    void zipWithoutJsonlIsNotClaimedByMetabibReader() throws Exception {
        Path zip = temp.resolve("flibusta-update.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("books.inp"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertThat(reader.supports(zip)).isFalse();
    }


    @Test
    void rejectsZipDatasetWithSuspiciousCompressionRatioBeforeParsing() throws Exception {
        Path zip = temp.resolve("bomb.zip");
        byte[] highlyCompressible = new byte[512 * 1024];
        java.util.Arrays.fill(highlyCompressible, (byte) 'A');
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("dataset/books.jsonl"));
            out.write(highlyCompressible);
            out.closeEntry();
        }

        assertThat(reader.supports(zip)).isFalse();
        assertThatThrownBy(() -> reader.open(zip))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("suspicious compression ratio");
    }

    @Test
    void rejectsWrongDatasetHeaderBeforeRecordsAreImported() throws Exception {
        Path jsonl = temp.resolve("wrong.jsonl");
        Files.writeString(jsonl, "{\"schema\":\"other/1\",\"record_schema\":\"metabib.dataset_record/1\"}\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.open(jsonl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported metabib dataset schema");
    }

    @Test
    void rejectsDatasetReachedThroughSymlinkedParent() throws Exception {
        Path real = Files.createDirectories(temp.resolve("real"));
        Path jsonl = real.resolve("books.jsonl");
        Files.writeString(jsonl, dataset(), StandardCharsets.UTF_8);
        Path alias = temp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem");
        }

        Path viaAlias = alias.resolve("books.jsonl");
        assertThat(reader.supports(viaAlias)).isFalse();
        assertThatThrownBy(() -> reader.open(viaAlias))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void archiveEntriesWithSameIndexInDifferentZipsKeepDistinctSourceBookIds() throws Exception {
        String header = """
                {"schema":"metabib.dataset/1","id":"two-archives","record_schema":"metabib.dataset_record/1","library":"flibusta","created":"2026-10-27T00:00:00Z","records":2,"generator":{"name":"test","version":"1"},"normalization":{"model":"metabib.norm/1"},"ordering":{"mode":"archive_entry","direction":"ascending"},"processing":{"parse_fb2":true,"archive_content_checksum":{"enabled":false}},"archives":[{"id":"archive-a","name":"a.zip","ordinal":0,"entries":1,"fb2_entries":1},{"id":"archive-b","name":"b.zip","ordinal":1,"entries":1,"fb2_entries":1}]}
                """;
        String a = """
                {"schema":"metabib.dataset_record/1","record":{"library":"flibusta","locator":{"kind":"archive_entry","source":"archive-a","index":0}},"identities":{},"artifacts":[{"name":"a.fb2","media_type":"application/fb2+xml","occurrences":[{"archive":"archive-a","entry":"a.fb2","index":0}]}],"observations":[],"claims":{"bibliographic":{"title":[{"value":"Alpha"}]},"publication":{},"catalog":{}},"issues":[]}
                """;
        String b = """
                {"schema":"metabib.dataset_record/1","record":{"library":"flibusta","locator":{"kind":"archive_entry","source":"archive-b","index":0}},"identities":{},"artifacts":[{"name":"b.fb2","media_type":"application/fb2+xml","occurrences":[{"archive":"archive-b","entry":"b.fb2","index":0}]}],"observations":[],"claims":{"bibliographic":{"title":[{"value":"Beta"}]},"publication":{},"catalog":{}},"issues":[]}
                """;
        Path source = temp.resolve("two-archives.jsonl");
        Files.writeString(source, header + a + b, StandardCharsets.UTF_8);

        try (CatalogReadSession session = reader.open(source)) {
            CatalogRecord first = session.next();
            CatalogRecord second = session.next();
            assertThat(first.title()).isEqualTo("Alpha");
            assertThat(second.title()).isEqualTo("Beta");
            assertThat(first.sourceBookId()).contains("archive-a");
            assertThat(second.sourceBookId()).contains("archive-b");
            assertThat(first.sourceBookId()).isNotEqualTo(second.sourceBookId());
            assertThat(first.archive()).isEqualTo("a.zip");
            assertThat(first.archiveEntry()).isEqualTo("a.fb2");
            assertThat(second.archive()).isEqualTo("b.zip");
            assertThat(second.archiveEntry()).isEqualTo("b.fb2");
            assertThat(first.sourceMetadata()).containsEntry("locator.index", "0")
                    .containsEntry("locator.source", "archive-a");
            assertThat(second.sourceMetadata()).containsEntry("locator.source", "archive-b");
            assertThat(session.hasNext()).isFalse();
        }
    }

    @Test
    void archiveLocatorWithCatalogIdentityPreservesFlibustaBookIdForDownstreamImport() throws Exception {
        String header = """
                {"schema":"metabib.dataset/1","id":"identity","record_schema":"metabib.dataset_record/1","library":"flibusta","created":"2026-10-27T00:00:00Z","records":1,"generator":{"name":"test","version":"1"},"normalization":{"model":"metabib.norm/1"},"ordering":{"mode":"archive_entry","direction":"ascending"},"processing":{"parse_fb2":true,"archive_content_checksum":{"enabled":false}},"archives":[]}
                """;
        String record = """
                {"schema":"metabib.dataset_record/1","record":{"library":"flibusta","locator":{"kind":"archive_entry","source":"archive-c","index":0}},"identities":{"catalog":[{"scheme":"flibusta","value":"893718"}]},"artifacts":[],"observations":[],"claims":{"bibliographic":{"title":[{"value":"Identity"}]}},"issues":[]}
                """;
        Path source = temp.resolve("identity.jsonl");
        Files.writeString(source, header + record, StandardCharsets.UTF_8);
        try (CatalogReadSession session = reader.open(source)) {
            CatalogRecord book = session.next();
            assertThat(book.externalIdentities()).anySatisfy(identity -> {
                assertThat(identity.scheme()).isEqualTo("catalog:flibusta");
                assertThat(identity.value()).isEqualTo("893718");
            });
            assertThat(book.sourceMetadata()).containsEntry("locator.index", "0")
                    .doesNotContainKey("locator.book_id");
            assertThat(session.hasNext()).isFalse();
        }
    }


    @Test
    void prefersDatabaseGenreDefinitionsAndDoesNotMergeFb2Tags() throws Exception {
        String header = """
                {"schema":"metabib.dataset/1","id":"genre-priority","record_schema":"metabib.dataset_record/1","library":"flibusta","created":"2026-11-01T00:00:00Z","records":1,"generator":{"name":"test","version":"1"},"normalization":{"model":"metabib.norm/1"},"ordering":{"mode":"database_book_id","direction":"ascending"},"processing":{"parse_fb2":false,"archive_content_checksum":{"enabled":false}},"archives":[]}
                """;
        String record = """
                {"schema":"metabib.dataset_record/1","record":{"library":"flibusta","locator":{"kind":"database_book","source":"db","book_id":1}},"identities":{},"artifacts":[],"observations":[{"id":"db"},{"id":"fb2"}],"claims":{"bibliographic":{"title":[{"observation":"db","value":"Genre priority"}],"genres":[{"observation":"fb2","value":[{"code":"untrusted_tag","description":"FB2 label","meta":"FB2 category"}]},{"observation":"db","value":[{"code":"mhl-1-sf","description":"Catalog description","meta":"Література","translated_code":"sf"}]}]},"publication":{},"catalog":{}},"issues":[]}
                """;
        Path source = temp.resolve("genre-priority.jsonl");
        Files.writeString(source, header + record, StandardCharsets.UTF_8);

        try (CatalogReadSession session = reader.open(source)) {
            CatalogRecord imported = session.next();
            assertThat(imported.genres()).containsExactly("mhl-1-sf");
            assertThat(imported.genreDefinitions()).containsExactly(new CatalogGenreDefinition(
                    "mhl-1-sf", "Catalog description", "Література", "sf", true));
            assertThat(session.hasNext()).isFalse();
        }
    }

    @Test
    void fallsBackToFb2GenresOnlyWhenDatabaseDefinitionIsNotUsable() throws Exception {
        String header = """
                {"schema":"metabib.dataset/1","id":"genre-fallback","record_schema":"metabib.dataset_record/1","library":"flibusta","created":"2026-11-01T00:00:00Z","records":1,"generator":{"name":"test","version":"1"},"normalization":{"model":"metabib.norm/1"},"ordering":{"mode":"database_book_id","direction":"ascending"},"processing":{"parse_fb2":false,"archive_content_checksum":{"enabled":false}},"archives":[]}
                """;
        String record = """
                {"schema":"metabib.dataset_record/1","record":{"library":"flibusta","locator":{"kind":"database_book","source":"db","book_id":2}},"identities":{},"artifacts":[],"observations":[{"id":"db"},{"id":"fb2"}],"claims":{"bibliographic":{"title":[{"observation":"db","value":"Genre fallback"}],"genres":[{"observation":"db","value":[{"description":"Missing code"}]},{"observation":"fb2","value":[{"code":"sf","description":"FB2 description","meta":"FB2 category"}]}]},"publication":{},"catalog":{}},"issues":[]}
                """;
        Path source = temp.resolve("genre-fallback.jsonl");
        Files.writeString(source, header + record, StandardCharsets.UTF_8);

        try (CatalogReadSession session = reader.open(source)) {
            CatalogRecord imported = session.next();
            assertThat(imported.genres()).containsExactly("sf");
            assertThat(imported.genreDefinitions()).containsExactly(new CatalogGenreDefinition(
                    "sf", "FB2 description", "FB2 category", "", false));
            assertThat(session.hasNext()).isFalse();
        }
    }

    private static String dataset() {
        return """
                {"schema":"metabib.dataset/1","id":"test","record_schema":"metabib.dataset_record/1","library":"flibusta","created":"2026-08-30T00:00:00Z","records":1,"generator":{"name":"test","version":"1"},"normalization":{"model":"metabib.norm/1"},"ordering":{"mode":"database_book_id","direction":"ascending"},"processing":{"parse_fb2":true,"archive_content_checksum":{"enabled":false}},"archives":[]}
                {"schema":"metabib.dataset_record/1","record":{"library":"flibusta","locator":{"kind":"database_book","source":"db","book_id":42}},"identities":{"catalog":[{"scheme":"flibusta","value":"42","observation":"db"}]},"artifacts":[{"name":"42.fb2","media_type":"application/fb2+xml","checksums":[]}],"observations":[{"id":"db"}],"claims":{"bibliographic":{"title":[{"observation":"db","value":"Book title"}],"authors":[{"observation":"db","value":{"first_name":"Ann|Pipe","middle_name":"","last_name":"Smith","identities":[{"scheme":"flibusta-person","value":"7"}]}}],"language":[{"observation":"db","value":"English"}]},"publication":{},"catalog":{}},"issues":[]}
                """;
    }
}
