package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraphAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpubStructuralValidatorTest {
    @TempDir Path tempDir;
    private final EpubStructuralValidator validator = new EpubStructuralValidator(new EpubResourceGraphAnalyzer());

    @Test
    void acceptsMinimalOcFCompliantEpub() throws Exception {
        Path epub = createEpub("valid.epub", true, true, false);

        assertThatCode(() -> validator.validate(epub)).doesNotThrowAnyException();
    }

    @Test
    void acceptsStoredMimetypeWhenLocalHeaderUsesDataDescriptorFlag() throws Exception {
        Path epub = createEpub("descriptor-flag.epub", true, true, false);
        byte[] bytes = Files.readAllBytes(epub);
        // General-purpose bit 3 is enough to reproduce the JDK ZipInputStream failure for a STORED
        // first entry; ZipFile still reads the central-directory metadata correctly.
        bytes[6] = (byte) (bytes[6] | 0x08);
        Files.write(epub, bytes);

        assertThatCode(() -> validator.validate(epub)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMimetypeWhenItIsDeflated() throws Exception {
        Path epub = createEpub("deflated-mimetype.epub", false, true, false);

        assertThatThrownBy(() -> validator.validate(epub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mimetype must be stored");
    }

    @Test
    void rejectsTraversalZipEntry() throws Exception {
        Path epub = createEpub("traversal.epub", true, true, true);

        assertThatThrownBy(() -> validator.validate(epub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe EPUB ZIP entry");
    }

    @Test
    void rejectsManifestReferenceWhoseResourceIsMissing() throws Exception {
        Path epub = createEpub("missing-resource.epub", true, false, false);

        assertThatThrownBy(() -> validator.validate(epub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manifest resource is missing");
    }

    private Path createEpub(String name, boolean storedMimetype, boolean includeChapter, boolean traversal) throws Exception {
        Path epub = tempDir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub), StandardCharsets.UTF_8)) {
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry mime = new ZipEntry("mimetype");
            if (storedMimetype) {
                CRC32 crc = new CRC32();
                crc.update(mimetype);
                mime.setMethod(ZipEntry.STORED);
                mime.setSize(mimetype.length);
                mime.setCompressedSize(mimetype.length);
                mime.setCrc(crc.getValue());
            }
            zip.putNextEntry(mime);
            zip.write(mimetype);
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
                        <dc:identifier id="id">urn:test</dc:identifier><dc:title>Test</dc:title><dc:language>uk</dc:language>
                      </metadata>
                      <manifest><item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="c1"/></spine>
                    </package>
                    """);
            if (includeChapter) {
                write(zip, "OEBPS/chapter.xhtml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Test</title></head><body><p>Hello</p></body></html>
                        """);
            }
            if (traversal) write(zip, "../evil.txt", "evil");
        }
        return epub;
    }

    private static void write(ZipOutputStream zip, String name, String value) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
