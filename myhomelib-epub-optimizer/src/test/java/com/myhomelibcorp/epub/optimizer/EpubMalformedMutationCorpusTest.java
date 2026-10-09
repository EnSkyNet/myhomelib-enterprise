package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.graph.EpubResourceGraphAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic malformed container/OPF XML corpus for structural EPUB validation. */
class EpubMalformedMutationCorpusTest {
    @TempDir Path temp;
    private final EpubStructuralValidator validator = new EpubStructuralValidator(new EpubResourceGraphAnalyzer());

    @Test
    void malformedXmlMutationsFailClosed() throws Exception {
        List<String> containers = List.of(
                "<container>",
                "<?xml version=\"1.0\"?><container><rootfiles>",
                "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><container>&xxe;</container>");
        for (int i = 0; i < containers.size(); i++) {
            Path epub = create("bad-container-" + i + ".epub", containers.get(i), validOpf());
            assertThatThrownBy(() -> validator.validate(epub))
                    .as("malformed container.xml mutation %s", i)
                    .isInstanceOfAny(IllegalArgumentException.class, java.io.IOException.class, javax.xml.stream.XMLStreamException.class);
        }

        List<String> opfs = List.of(
                "<package>",
                validOpf().replace("</manifest>", "</broken>"),
                "<?xml version=\"1.0\"?><package><manifest><item id=\"x\"");
        for (int i = 0; i < opfs.size(); i++) {
            Path epub = create("bad-opf-" + i + ".epub", validContainer(), opfs.get(i));
            assertThatThrownBy(() -> validator.validate(epub))
                    .as("malformed OPF mutation %s", i)
                    .isInstanceOfAny(IllegalArgumentException.class, java.io.IOException.class, javax.xml.stream.XMLStreamException.class);
        }
    }

    private Path create(String name, String container, String opf) throws Exception {
        Path epub = temp.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(epub), StandardCharsets.UTF_8)) {
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32(); crc.update(mimetype);
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED); mime.setSize(mimetype.length); mime.setCompressedSize(mimetype.length); mime.setCrc(crc.getValue());
            zip.putNextEntry(mime); zip.write(mimetype); zip.closeEntry();
            write(zip, "META-INF/container.xml", container);
            write(zip, "OEBPS/content.opf", opf);
            write(zip, "OEBPS/chapter.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>x</p></body></html>");
        }
        return epub;
    }

    private static String validContainer() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """;
    }

    private static String validOpf() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:test</dc:identifier><dc:title>T</dc:title><dc:language>uk</dc:language></metadata>
                  <manifest><item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine>
                </package>
                """;
    }

    private static void write(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
