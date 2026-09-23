package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.graph.EpubExactDuplicateDeduplicator;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraphAnalyzer;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourcePruner;
import com.myhomelibcorp.epub.optimizer.graph.EpubResourceReplacementWriter;
import com.myhomelibcorp.epub.optimizer.graph.EpubSafeMarkupNormalizer;
import com.myhomelibcorp.epub.optimizer.packaging.DeterministicEpubPackager;
import com.myhomelibcorp.epub.optimizer.packaging.EpubPackagingProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Regression for the OCF rule that the first mimetype entry is STORED and has no ZIP extra field. */
class OcfMimetypeWriterInvariantTest {
    private static final byte[] MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path tempDir;

    private final EpubResourceGraphAnalyzer graphAnalyzer = new EpubResourceGraphAnalyzer();
    private final EpubStructuralValidator validator = new EpubStructuralValidator(graphAnalyzer);

    @Test
    void allOptimizerZipWritersPreserveStrictMimetypeHeader() throws Exception {
        Path source = createFixture();
        assertOcfMimetype(source);

        Path normalized = tempDir.resolve("normalized.epub");
        var normalization = new EpubSafeMarkupNormalizer().normalize(source, normalized, graphAnalyzer.analyze(source));
        assertThat(normalization.changedResources()).isGreaterThan(0);
        assertOcfMimetype(normalized);

        Path deduplicated = tempDir.resolve("deduplicated.epub");
        int deduplicatedCount = new EpubExactDuplicateDeduplicator()
                .deduplicate(normalized, deduplicated, graphAnalyzer.analyze(normalized));
        assertThat(deduplicatedCount).isEqualTo(1);
        assertOcfMimetype(deduplicated);

        Path pruned = tempDir.resolve("pruned.epub");
        var prunedGraph = graphAnalyzer.analyze(deduplicated);
        assertThat(prunedGraph.safelyPrunableOrphans()).contains("OEBPS/images/orphan.png");
        int prunedCount = new EpubResourcePruner()
                .prune(deduplicated, pruned, prunedGraph, Set.of("OEBPS/images/orphan.png"));
        assertThat(prunedCount).isEqualTo(1);
        assertOcfMimetype(pruned);

        Path replacement = tempDir.resolve("replacement.png");
        Files.write(replacement, new byte[]{9, 8, 7, 6, 5});
        Path replaced = tempDir.resolve("replaced.epub");
        int replacementCount = new EpubResourceReplacementWriter()
                .replace(pruned, replaced, Map.of("OEBPS/images/a.png", replacement));
        assertThat(replacementCount).isEqualTo(1);
        assertOcfMimetype(replaced);

        Path repacked = tempDir.resolve("repacked.epub");
        new DeterministicEpubPackager().repack(replaced, repacked, EpubPackagingProfile.compatible());
        assertOcfMimetype(repacked);
    }

    private void assertOcfMimetype(Path epub) throws Exception {
        assertThatCode(() -> validator.validate(epub)).doesNotThrowAnyException();
        try (InputStream raw = Files.newInputStream(epub);
             ZipInputStream zip = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
            ZipEntry first = zip.getNextEntry();
            assertThat(first).isNotNull();
            assertThat(first.getName()).isEqualTo("mimetype");
            assertThat(first.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(first.getExtra()).isNullOrEmpty();
            assertThat(zip.readNBytes(MIMETYPE.length + 1)).containsExactly(MIMETYPE);
        }
    }

    private Path createFixture() throws Exception {
        Path epub = tempDir.resolve("source.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub), StandardCharsets.UTF_8)) {
            CRC32 crc = new CRC32();
            crc.update(MIMETYPE);
            ZipEntry mimetype = new ZipEntry("mimetype");
            mimetype.setMethod(ZipEntry.STORED);
            mimetype.setSize(MIMETYPE.length);
            mimetype.setCompressedSize(MIMETYPE.length);
            mimetype.setCrc(crc.getValue());
            mimetype.setExtra(null);
            zip.putNextEntry(mimetype);
            zip.write(MIMETYPE);
            zip.closeEntry();

            write(zip, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
            write(zip, "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="id">urn:ocf-regression</dc:identifier>
                        <dc:title>OCF writer regression</dc:title>
                        <dc:language>en</dc:language>
                      </metadata>
                      <manifest>
                        <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                        <item id="a" href="images/a.png" media-type="image/png"/>
                        <item id="b" href="images/b.png" media-type="image/png"/>
                        <item id="orphan" href="images/orphan.png" media-type="image/png"/>
                      </manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """);
            write(zip, "OEBPS/chapter.xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Test</title></head>\r\n"
                    + "<body><img src=\"images/b.png\" alt=\"cover\"/><p>Hello</p></body></html>\r\n");
            write(zip, "OEBPS/images/a.png", new byte[]{1, 2, 3, 4});
            write(zip, "OEBPS/images/b.png", new byte[]{1, 2, 3, 4});
            write(zip, "OEBPS/images/orphan.png", new byte[]{4, 3, 2, 1});
        }
        return epub;
    }

    private static void write(ZipOutputStream zip, String name, String value) throws Exception {
        write(zip, name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(ZipOutputStream zip, String name, byte[] value) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(value);
        zip.closeEntry();
    }
}
