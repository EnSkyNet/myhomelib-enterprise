package com.myhomelibcorp.infrastructure.catalog.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdOutputStream;
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

class MetabibCatalogReaderTest {
    @TempDir Path temp;
    private final MetabibCatalogReader reader = new MetabibCatalogReader(new ObjectMapper());

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
    void rejectsWrongDatasetHeaderBeforeRecordsAreImported() throws Exception {
        Path jsonl = temp.resolve("wrong.jsonl");
        Files.writeString(jsonl, "{\"schema\":\"other/1\",\"record_schema\":\"metabib.dataset_record/1\"}\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.open(jsonl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported metabib dataset schema");
    }

    private static String dataset() {
        return """
                {"schema":"metabib.dataset/1","id":"test","record_schema":"metabib.dataset_record/1","library":"flibusta","created":"2026-08-30T00:00:00Z","records":1,"generator":{"name":"test","version":"1"},"normalization":{"model":"metabib.norm/1"},"ordering":{"mode":"database_book_id","direction":"ascending"},"processing":{"parse_fb2":true,"archive_content_checksum":{"enabled":false}},"archives":[]}
                {"schema":"metabib.dataset_record/1","record":{"library":"flibusta","locator":{"kind":"database_book","source":"db","book_id":42}},"identities":{"catalog":[{"scheme":"flibusta","value":"42","observation":"db"}]},"artifacts":[{"name":"42.fb2","media_type":"application/fb2+xml","checksums":[]}],"observations":[{"id":"db"}],"claims":{"bibliographic":{"title":[{"observation":"db","value":"Book title"}],"authors":[{"observation":"db","value":{"first_name":"Ann|Pipe","middle_name":"","last_name":"Smith","identities":[{"scheme":"flibusta-person","value":"7"}]}}],"language":[{"observation":"db","value":"English"}]},"publication":{},"catalog":{}},"issues":[]}
                """;
    }
}
